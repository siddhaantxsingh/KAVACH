"""Train and evaluate the Network Watch flow model (per-connection intrusion / malware traffic).

Datasets (data/public/download.sh -> data/public/raw/, never committed):
  UNSW-NB15 (Moustafa & Slay 2015) official training (175,341) / testing (82,332) partitions.
  NSL-KDD   (Tavallaee et al. 2009) KDDTrain+ / KDDTest+ (the test set has 17 attack types
            that never appear in training: a built-in "new attack" test).
CICIDS2017 was not reachable from the build machine; see docs/network_watch.md.

KEY FINDING: UNSW-NB15 contains ZERO normal TLS/HTTPS flows (both partitions). A model trained on it
calls ordinary phone HTTPS "attack" (60/60 normal HTTPS sessions in bench/netwatch_pcap_demo.py).
The model is therefore kept as an evaluated research component and raises no alerts; Network
Watch alerts come from explainable rules (kavach_core/netwatch.py, bench/netwatch_pcap_demo.py).

Design choices (honesty first):
  * PHONE-OBSERVABLE features only: what a flow tracker inside a phone VPN can count
    (duration, packets, bytes, rates, mean packet size, protocol/state/service, and how many
    recent connections share the destination / service). TTL, TCP sequence numbers, window
    sizes and payload-derived fields are excluded: TTL in UNSW-NB15 is a known testbed artefact
    (it nearly separates attacks by itself) and a phone app cannot rely on it. We report a
    model WITH those fields only to show how much of the usual headline number is artefact.
  * Threshold picked on a validation slice of the training partition for <= 2% false alarms;
    the testing partition is used once.
  * Leave-one-attack-out: retrain without one attack category and measure how much of that
    category is still caught (new-attack generalisation).
Outputs: assets/models/flow_model.json (kavach-mlp-v1, Kotlin-portable), assets/models/flow_model.pte
(ExecuTorch, XNNPACK), bench/results/netwatch_ids.json.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import f1_score, precision_score, recall_score, roc_auc_score

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from kavach_core.netwatch import FLOW_CAT, FLOW_NUM, FlowModel, flow_vector  # noqa: E402

RAW = ROOT / "data/public/raw"
TARGET_FPR = 0.02
SEED = 7


def load_unsw():
    tr = pd.read_csv(RAW / "UNSW_NB15_training-set.csv", encoding="utf-8-sig")
    te = pd.read_csv(RAW / "UNSW_NB15_testing-set.csv", encoding="utf-8-sig")
    # the public CSVs are swapped relative to their names (the "training" file is the larger one is
    # correct: 175,341 rows); keep the official roles.
    for d in (tr, te):
        d["attack_cat"] = d["attack_cat"].fillna("Normal").str.strip().replace({"Backdoors": "Backdoor"})
    return tr, te


def vec(df: pd.DataFrame) -> np.ndarray:
    cols = FLOW_NUM + list(FLOW_CAT)
    recs = df[cols].to_dict("records")
    return np.asarray([flow_vector(r) for r in recs], dtype=np.float32)


class MLP(torch.nn.Module):
    def __init__(self, d_in: int, mean: np.ndarray, std: np.ndarray):
        super().__init__()
        self.register_buffer("mean", torch.tensor(mean))
        self.register_buffer("std", torch.tensor(std))
        self.net = torch.nn.Sequential(torch.nn.Linear(d_in, 32), torch.nn.ReLU(), torch.nn.Linear(32, 16), torch.nn.ReLU(), torch.nn.Linear(16, 1))

    def forward(self, x):  # raw flow_vector in, probability out
        return torch.sigmoid(self.net((x - self.mean) / self.std))


def train_mlp(X: np.ndarray, y: np.ndarray, epochs: int = 12) -> MLP:
    torch.manual_seed(SEED)
    mean = X.mean(0)
    std = X.std(0) + 1e-6
    m = MLP(X.shape[1], mean, std)
    opt = torch.optim.Adam(m.parameters(), lr=2e-3, weight_decay=1e-5)
    pos_w = torch.tensor((y == 0).sum() / max((y == 1).sum(), 1), dtype=torch.float32)
    Xt, yt = torch.tensor(X), torch.tensor(y, dtype=torch.float32)
    g = torch.Generator().manual_seed(SEED)
    for _ in range(epochs):
        perm = torch.randperm(len(Xt), generator=g)
        for i in range(0, len(Xt), 512):
            b = perm[i:i + 512]
            logit = m.net((Xt[b] - m.mean) / m.std).squeeze(1)
            loss = torch.nn.functional.binary_cross_entropy_with_logits(logit, yt[b], pos_weight=pos_w)
            opt.zero_grad()
            loss.backward()
            opt.step()
    return m.eval()


def mlp_scores(m: MLP, X: np.ndarray) -> np.ndarray:
    with torch.no_grad():
        return m(torch.tensor(X)).squeeze(1).numpy()


def threshold_at_fpr(scores: np.ndarray, y: np.ndarray, fpr: float) -> float:
    neg = np.sort(scores[y == 0])
    return float(neg[min(int(len(neg) * (1 - fpr)), len(neg) - 1)])


def report(scores, y, cats, thr) -> dict:
    pred = scores > thr
    out = {
        "precision": round(float(precision_score(y, pred)), 4),
        "recall": round(float(recall_score(y, pred)), 4),
        "f1": round(float(f1_score(y, pred)), 4),
        "false_alarm_rate": round(float(pred[y == 0].mean()), 4),
        "roc_auc": round(float(roc_auc_score(y, scores)), 4),
        "per_attack": {},
    }
    for c in sorted(set(cats)):
        msk = cats == c
        if c == "Normal":
            continue
        # per-attack precision: attack flows of this type flagged vs. all flagged normal flows (one-vs-normal)
        tp = int(pred[msk].sum())
        fp = int(pred[y == 0].sum())
        rec = tp / msk.sum()
        prec = tp / (tp + fp) if tp + fp else 0.0
        out["per_attack"][c] = {"n": int(msk.sum()), "recall": round(rec, 4), "precision_vs_normal": round(prec, 4),
                                "f1": round(2 * prec * rec / (prec + rec), 4) if prec + rec else 0.0}
    return out


def export_json(m: MLP, thr: float, path: Path, meta: dict) -> None:
    layers = []
    for mod in m.net:
        if isinstance(mod, torch.nn.Linear):
            layers.append({"w": [[round(float(v), 7) for v in row] for row in mod.weight.detach().numpy()],
                           "b": [round(float(v), 7) for v in mod.bias.detach().numpy()]})
    spec = {"format": "kavach-mlp-v1", "features_num": FLOW_NUM, "features_cat": FLOW_CAT,
            "transform": "log1p(num) + one-hot(cat)+other; (x-mean)/std", "mean": [float(v) for v in m.mean.numpy()],
            "std": [float(v) for v in m.std.numpy()], "layers": layers, "threshold": thr, **meta}
    path.write_text(json.dumps(spec), encoding="utf-8")


def export_pte(m: MLP, d_in: int, path: Path) -> str:
    try:
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
        from executorch.exir import to_edge_transform_and_lower

        ep = torch.export.export(m, (torch.zeros(1, d_in),))
        prog = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()
        path.write_bytes(prog.buffer)
        return f"ok ({path.stat().st_size} bytes)"
    except Exception as e:  # noqa: BLE001
        return f"skipped: {type(e).__name__}: {e}"[:300]


def unsw() -> dict:
    tr, te = load_unsw()
    rng = np.random.default_rng(SEED)
    val_mask = rng.random(len(tr)) < 0.1
    Xtr_all, Xte = vec(tr), vec(te)
    ytr_all, yte = tr["label"].to_numpy(), te["label"].to_numpy()
    ctr_all, cte = tr["attack_cat"].to_numpy(), te["attack_cat"].to_numpy()
    Xtr, ytr, Xv, yv = Xtr_all[~val_mask], ytr_all[~val_mask], Xtr_all[val_mask], ytr_all[val_mask]
    res: dict = {"dataset": "UNSW-NB15 official partitions",
                 "normal_tls_flows": {"train": int(((tr.service == "ssl") & (tr.label == 0)).sum()), "test": int(((te.service == "ssl") & (te.label == 0)).sum())},
                 "deployment_status": "research only, raises no alerts: UNSW-NB15 has no normal TLS/HTTPS flows, so ordinary phone "
                                      "HTTPS is scored as attack (see bench/netwatch_pcap_demo.py). Needs retraining on phone traffic.", "n_train": int(len(Xtr)), "n_val": int(len(Xv)), "n_test": int(len(Xte)),
                 "target_val_false_alarm": TARGET_FPR, "features": FLOW_NUM + list(FLOW_CAT)}

    mlp = train_mlp(Xtr, ytr)
    thr = threshold_at_fpr(mlp_scores(mlp, Xv), yv, TARGET_FPR)
    res["mlp_phone_features"] = report(mlp_scores(mlp, Xte), yte, cte, thr)
    res["mlp_params"] = int(sum(p.numel() for p in mlp.net.parameters()))

    gbt = HistGradientBoostingClassifier(max_iter=300, learning_rate=0.1, random_state=SEED).fit(Xtr, ytr)
    thr_g = threshold_at_fpr(gbt.predict_proba(Xv)[:, 1], yv, TARGET_FPR)
    res["gbt_phone_features"] = report(gbt.predict_proba(Xte)[:, 1], yte, cte, thr_g)

    # "all features" reference (includes TTL & other testbed-specific fields) - NOT shipped
    drop = {"id", "label", "attack_cat"}
    num_all = [c for c in tr.columns if c not in drop and pd.api.types.is_numeric_dtype(tr[c])]
    Xa_tr = np.hstack([tr[num_all].to_numpy(np.float32), Xtr_all[:, len(FLOW_NUM):]])
    Xa_te = np.hstack([te[num_all].to_numpy(np.float32), Xte[:, len(FLOW_NUM):]])
    gbt_a = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(Xa_tr[~val_mask], ytr)
    thr_a = threshold_at_fpr(gbt_a.predict_proba(Xa_tr[val_mask])[:, 1], yv, TARGET_FPR)
    res["gbt_all_features_reference"] = report(gbt_a.predict_proba(Xa_te)[:, 1], yte, cte, thr_a)
    ttl = [c for c in num_all if "ttl" in c]
    gbt_ttl = HistGradientBoostingClassifier(max_iter=100, random_state=SEED).fit(tr[ttl].to_numpy()[~val_mask], ytr)
    res["ttl_only_reference_auc"] = round(float(roc_auc_score(yte, gbt_ttl.predict_proba(te[ttl].to_numpy())[:, 1])), 4)

    # leave-one-attack-out (MLP, phone features): train without category c, recall on c in test
    loao = {}
    for c in sorted(set(ctr_all) - {"Normal"}):
        keep = (~val_mask) & (ctr_all != c)
        m_c = train_mlp(Xtr_all[keep], ytr_all[keep], epochs=6)
        vk = val_mask & (ctr_all != c)
        t_c = threshold_at_fpr(mlp_scores(m_c, Xtr_all[vk]), ytr_all[vk], TARGET_FPR)
        s = mlp_scores(m_c, Xte)
        loao[c] = {"recall_when_unseen": round(float((s[cte == c] > t_c).mean()), 4),
                   "recall_when_seen": res["mlp_phone_features"]["per_attack"][c]["recall"]}
        print("loao", c, loao[c], flush=True)
    res["leave_one_attack_out"] = loao

    export_json(mlp, thr, ROOT / "assets/models/flow_model.json",
                {"trained_on": "UNSW-NB15 training partition (phone-observable features)", "val_false_alarm_target": TARGET_FPR,
                 "status": "research only: not used for alerts (UNSW-NB15 has zero normal TLS flows; phone traffic is mostly TLS)"})
    res["pte_export"] = export_pte(mlp, Xtr.shape[1], ROOT / "assets/models/flow_model.pte")
    # check the JSON model reproduces torch on 200 test flows
    fm = FlowModel.load(ROOT / "assets/models/flow_model.json")
    recs = te[FLOW_NUM + list(FLOW_CAT)].head(200).to_dict("records")
    diff = max(abs(fm.score(r) - p) for r, p in zip(recs, mlp_scores(mlp, Xte[:200])))
    res["json_vs_torch_max_abs_diff"] = float(diff)
    return res


NSL_COLS = ["duration", "protocol_type", "service", "flag", "src_bytes", "dst_bytes", "land", "wrong_fragment", "urgent", "hot",
            "num_failed_logins", "logged_in", "num_compromised", "root_shell", "su_attempted", "num_root", "num_file_creations",
            "num_shells", "num_access_files", "num_outbound_cmds", "is_host_login", "is_guest_login", "count", "srv_count",
            "serror_rate", "srv_serror_rate", "rerror_rate", "srv_rerror_rate", "same_srv_rate", "diff_srv_rate",
            "srv_diff_host_rate", "dst_host_count", "dst_host_srv_count", "dst_host_same_srv_rate", "dst_host_diff_srv_rate",
            "dst_host_same_src_port_rate", "dst_host_srv_diff_host_rate", "dst_host_serror_rate", "dst_host_srv_serror_rate",
            "dst_host_rerror_rate", "dst_host_srv_rerror_rate", "label", "difficulty"]
# phone-observable: connection header stats + time/host window counts; NO payload-content fields (hot, logins, shells...)
NSL_PHONE_NUM = ["duration", "src_bytes", "dst_bytes", "wrong_fragment", "count", "srv_count", "serror_rate", "rerror_rate",
                 "same_srv_rate", "diff_srv_rate", "dst_host_count", "dst_host_srv_count", "dst_host_same_srv_rate",
                 "dst_host_diff_srv_rate", "dst_host_serror_rate", "dst_host_rerror_rate"]
NSL_FAMILY = {
    "dos": "back land neptune pod smurf teardrop apache2 mailbomb processtable udpstorm",
    "probe": "ipsweep nmap portsweep satan mscan saint",
    "r2l": "ftp_write guess_passwd imap multihop phf spy warezclient warezmaster httptunnel named sendmail snmpgetattack snmpguess worm xlock xsnoop",
    "u2r": "buffer_overflow loadmodule perl rootkit ps sqlattack xterm",
}


def nsl() -> dict:
    fam = {a: f for f, s in NSL_FAMILY.items() for a in s.split()}
    tr = pd.read_csv(RAW / "KDDTrain+.txt", names=NSL_COLS)
    te = pd.read_csv(RAW / "KDDTest+.txt", names=NSL_COLS)
    train_types = set(tr["label"])

    def X(d):
        num = np.log1p(d[NSL_PHONE_NUM].clip(lower=0).to_numpy(np.float64))
        cats = []
        for c, vals in (("protocol_type", ["tcp", "udp", "icmp"]), ("flag", ["SF", "S0", "REJ", "RSTR", "RSTO", "SH", "S1", "S2", "S3", "OTH"])):
            cats += [(d[c] == v).to_numpy(np.float64) for v in vals]
        return np.column_stack([num] + cats)

    ytr = (tr["label"] != "normal").to_numpy().astype(int)
    yte = (te["label"] != "normal").to_numpy().astype(int)
    rng = np.random.default_rng(SEED)
    vm = rng.random(len(tr)) < 0.1
    Xtr, Xte = X(tr), X(te)
    g = HistGradientBoostingClassifier(max_iter=300, random_state=SEED).fit(Xtr[~vm], ytr[~vm])
    thr = threshold_at_fpr(g.predict_proba(Xtr[vm])[:, 1], ytr[vm], TARGET_FPR)
    s = g.predict_proba(Xte)[:, 1]
    cats = np.array(["Normal" if l == "normal" else fam.get(l, "other") for l in te["label"]])
    r = report(s, yte, cats, thr)
    novel = np.array([l != "normal" and l not in train_types for l in te["label"]])
    pred = s > thr
    r["recall_on_attack_types_never_seen_in_training"] = round(float(pred[novel].mean()), 4)
    r["n_novel"] = int(novel.sum())
    r["recall_on_attack_types_seen_in_training"] = round(float(pred[(yte == 1) & ~novel].mean()), 4)
    r["note"] = "GBT on phone-observable NSL-KDD fields; threshold at 2% val false alarms; KDDTest+ used once"
    return {"dataset": "NSL-KDD KDDTrain+/KDDTest+", "gbt_phone_features": r}


def main() -> None:
    res = {"unsw_nb15": unsw(), "nsl_kdd": nsl()}
    (ROOT / "bench/results/netwatch_ids.json").write_text(json.dumps(res, indent=1), encoding="utf-8")
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
