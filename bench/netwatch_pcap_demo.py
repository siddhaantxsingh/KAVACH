"""Network Watch end-to-end demo on a packet capture (host side, for the judges' laptop).

  python bench/netwatch_pcap_demo.py                 # builds a synthetic capture, then analyses it
  python bench/netwatch_pcap_demo.py --pcap my.pcap  # analyse your own capture (e.g. from PCAPdroid)

Pipeline (the same one a full-tunnel phone VPN would run):
  packets -> bidirectional flows (5-tuple) -> UNSW-NB15-style flow statistics, incl. the
  "recent connections" counts -> FlowModel (assets/models/flow_model.json) per flow
  DNS packets -> DnsWatch (domain model + look-alike rules) -> malicious_domain
Alerts come from explainable rules on the flow statistics:
  port_scan          one remote host tries >= 20 different ports of the phone within 60 s, mostly refused
  beaconing          >= 6 connections from the phone to one host at a steady rhythm (CV <= 0.25)
  suspicious_upload  >= 2 MB sent by the phone to one host that sends little back
  malicious_domain   DnsWatch (machine-generated names that do not exist; bank look-alikes)
The UNSW-NB15 flow model is scored on every flow and REPORTED, but it raises no alert: UNSW-NB15
contains zero normal TLS/HTTPS flows (checked in both partitions), so the model calls ordinary
HTTPS "attack". It stays a research component until it is retrained on phone traffic
(docs/network_watch.md). Results: bench/results/netwatch_pcap_demo.json.

The synthetic capture is for demonstrating the pipeline; it is not an accuracy measurement
(the model was trained on the UNSW-NB15 testbed, a different network).
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import defaultdict, deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from kavach_core.features import LinearModel  # noqa: E402
from kavach_core.netwatch import DnsWatch, DomainModel, FlowModel, parse_dns  # noqa: E402
from kavach_core.url_check import UrlChecker  # noqa: E402

PHONE = "192.168.1.50"
SERVICES = {53: "dns", 80: "http", 443: "ssl", 21: "ftp", 20: "ftp-data", 25: "smtp", 22: "ssh", 110: "pop3"}


def build_capture(path: Path, seed: int = 3) -> None:
    from scapy.all import DNS, DNSQR, IP, TCP, UDP, Raw, wrpcap

    rng = random.Random(seed)
    pk = []
    t = 1_700_000_000.0

    def at(p, ts):
        p.time = ts
        pk.append(p)

    def tcp_session(ts, src, sport, dst, dport, up, down, close=True):
        at(IP(src=src, dst=dst) / TCP(sport=sport, dport=dport, flags="S"), ts)
        at(IP(src=dst, dst=src) / TCP(sport=dport, dport=sport, flags="SA"), ts + 0.02)
        at(IP(src=src, dst=dst) / TCP(sport=sport, dport=dport, flags="A"), ts + 0.04)
        x = ts + 0.05
        for i in range(max(1, up // 1200)):
            at(IP(src=src, dst=dst) / TCP(sport=sport, dport=dport, flags="PA") / Raw(b"u" * min(1200, up)), x)
            x += 0.002
        for i in range(max(1, down // 1400)):
            at(IP(src=dst, dst=src) / TCP(sport=dport, dport=sport, flags="PA") / Raw(b"d" * min(1400, down)), x)
            x += 0.002
        if close:
            at(IP(src=src, dst=dst) / TCP(sport=sport, dport=dport, flags="FA"), x + 0.01)
            at(IP(src=dst, dst=src) / TCP(sport=dport, dport=sport, flags="FA"), x + 0.03)

    def dns(ts, name, rcode=0, sport=None):
        sport = sport or rng.randint(30000, 60000)
        q = DNS(id=rng.randint(1, 65535), rd=1, qd=DNSQR(qname=name))
        at(IP(src=PHONE, dst="192.168.1.1") / UDP(sport=sport, dport=53) / q, ts)
        at(IP(src="192.168.1.1", dst=PHONE) / UDP(sport=53, dport=sport) / DNS(id=q.id, qr=1, rcode=rcode, qd=q.qd), ts + 0.03)

    # 1. normal browsing / apps
    sites = ["www.google.com", "www.youtube.com", "graph.facebook.com", "app-measurement.com", "onlinesbi.sbi", "www.amazon.in",
             "firebaseinstallations.googleapis.com", "i.ytimg.com", "web.whatsapp.com", "www.irctc.co.in"]
    for i in range(60):
        ts = t + i * 2.0
        dns(ts, rng.choice(sites))
        tcp_session(ts + 0.1, PHONE, rng.randint(40000, 60000), f"142.250.{rng.randint(0, 255)}.{rng.randint(1, 254)}", 443,
                    rng.randint(400, 3000), rng.randint(2000, 60000))
    # 2. a port scan of the phone from another device on the Wi-Fi (Reconnaissance)
    ts = t + 30
    for port in range(1, 400):
        at(IP(src="192.168.1.66", dst=PHONE) / TCP(sport=51515, dport=port, flags="S"), ts)
        at(IP(src=PHONE, dst="192.168.1.66") / TCP(sport=port, dport=51515, flags="RA"), ts + 0.001)
        ts += 0.01
    # 3. malware hunting for its control server: machine-generated names that do not exist
    for i, name in enumerate(["xjwqkzpvbnfhtrq.biz", "kdjfhgkdjfhgqwpz.ru", "qpwoeiruty1029.info", "zmxncbvlaksjdhq.top", "plmoknijbuhv7.net"]):
        dns(t + 60 + i * 4, name, rcode=3)
    # 4. a fake bank site
    dns(t + 80, "hdfcbnak.com")
    tcp_session(t + 80.2, PHONE, 45555, "45.33.12.9", 443, 2000, 30000)
    # 5. remote-control heartbeat every 30 s + a 2.4 MB upload (stolen photos)
    for i in range(8):
        tcp_session(t + 5 + i * 30, PHONE, 50000 + i, "203.0.113.9", 8443, 300, 200)
    tcp_session(t + 100, PHONE, 52222, "203.0.113.9", 8443, 2_400_000, 4000)
    pk.sort(key=lambda p: p.time)
    wrpcap(str(path), pk)


def flows_from(packets) -> list[dict]:
    from scapy.all import IP, TCP, UDP

    table: dict[tuple, dict] = {}
    order = []
    for p in packets:
        if IP not in p:
            continue
        ip = p[IP]
        proto = "tcp" if TCP in p else "udp" if UDP in p else "icmp" if ip.proto == 1 else str(ip.proto)
        sp = p[TCP].sport if TCP in p else p[UDP].sport if UDP in p else 0
        dp = p[TCP].dport if TCP in p else p[UDP].dport if UDP in p else 0
        k = (proto, ip.src, sp, ip.dst, dp)
        rk = (proto, ip.dst, dp, ip.src, sp)
        if k in table:
            f, fwd = table[k], True
        elif rk in table:
            f, fwd = table[rk], False
        else:
            f = {"key": k, "proto": proto, "src": ip.src, "sport": sp, "dst": ip.dst, "dport": dp, "start": float(p.time),
                 "last": float(p.time), "spkts": 0, "dpkts": 0, "sbytes": 0, "dbytes": 0, "syn": 0, "synack": 0, "fin": 0, "rst": 0}
            table[k] = f
            order.append(f)
            fwd = True
        f["last"] = float(p.time)
        n = int(ip.len)
        if fwd:
            f["spkts"] += 1
            f["sbytes"] += n
        else:
            f["dpkts"] += 1
            f["dbytes"] += n
        if TCP in p:
            fl = int(p[TCP].flags)
            f["syn"] |= int(bool(fl & 0x02) and not fl & 0x10)
            f["synack"] |= int(bool(fl & 0x02) and bool(fl & 0x10))
            f["fin"] |= int(bool(fl & 0x01))
            f["rst"] |= int(bool(fl & 0x04))
    recent: deque = deque(maxlen=100)
    out = []
    for f in sorted(order, key=lambda x: x["start"]):
        dur = f["last"] - f["start"]
        if f["proto"] == "tcp":
            state = "FIN" if f["fin"] else "RST" if f["rst"] else "CON" if f["synack"] else "REQ"
        elif f["proto"] == "udp":
            state = "CON" if f["dpkts"] else "INT"
        else:
            state = "-"
        svc = SERVICES.get(f["dport"], "-")
        rec = {"proto": f["proto"], "state": state, "service": svc, "dur": dur, "spkts": f["spkts"], "dpkts": f["dpkts"],
               "sbytes": f["sbytes"], "dbytes": f["dbytes"], "rate": (f["spkts"] + f["dpkts"] - 1) / dur if dur > 0 else 0.0,
               "smean": f["sbytes"] // max(f["spkts"], 1), "dmean": f["dbytes"] // max(f["dpkts"], 1)}
        recent.append((f["src"], f["dst"], f["sport"], f["dport"], svc))
        rec["ct_srv_src"] = sum(1 for r in recent if r[4] == svc and r[0] == f["src"])
        rec["ct_dst_ltm"] = sum(1 for r in recent if r[1] == f["dst"])
        rec["ct_src_dport_ltm"] = sum(1 for r in recent if r[0] == f["src"] and r[3] == f["dport"])
        rec["ct_dst_sport_ltm"] = sum(1 for r in recent if r[1] == f["dst"] and r[2] == f["sport"])
        rec["ct_dst_src_ltm"] = sum(1 for r in recent if r[0] == f["src"] and r[1] == f["dst"])
        rec["ct_srv_dst"] = sum(1 for r in recent if r[4] == svc and r[1] == f["dst"])
        rec.update({"t": f["start"], "src": f["src"], "dst": f["dst"], "sport": f["sport"], "dport": f["dport"]})
        out.append(rec)
    return out


def analyse(pcap: Path) -> dict:
    from scapy.all import DNS, UDP, rdpcap, raw

    pk = rdpcap(str(pcap))
    fm = FlowModel.load(ROOT / "assets/models/flow_model.json")
    dm = DomainModel.load(ROOT / "assets/models")
    uc = UrlChecker(LinearModel.load(ROOT / "assets/models", "url_model"))
    flows = flows_from(pk)
    t0 = flows[0]["t"] if flows else 0.0
    flagged = []
    for f in flows:
        s = fm.score(f)
        f["score"] = round(s, 4)
        if s >= fm.threshold:
            flagged.append(f)
    alerts = []
    # port scan: many distinct phone ports from one remote host in 60 s, mostly refused / unanswered
    inbound = defaultdict(list)
    for f in flows:
        if f["dst"] == PHONE and f["proto"] == "tcp":
            inbound[f["src"]].append(f)
    for host, fl in inbound.items():
        fl.sort(key=lambda x: x["t"])
        for i in range(len(fl)):
            win = [x for x in fl[i:] if x["t"] - fl[i]["t"] <= 60]
            ports = {x["dport"] for x in win}
            failed = sum(1 for x in win if x["state"] in ("RST", "REQ"))
            if len(ports) >= 20 and failed >= 0.8 * len(win):
                alerts.append({"kind": "port_scan", "remote": host, "first_s": round(fl[i]["t"] - t0, 2),
                               "what": f"{host} tried {len(ports)} ports of the phone within a minute"})
                break
    # beaconing: steady rhythm of connections phone -> one host
    outbound = defaultdict(list)
    for f in flows:
        if f["src"] == PHONE and f["proto"] == "tcp" and f["sbytes"] + f["dbytes"] <= 64000:  # small check-ins only
            outbound[f["dst"]].append(f["t"])
    for host, ts in outbound.items():
        ts.sort()
        if len(ts) >= 6:
            iv = [b - a for a, b in zip(ts, ts[1:])]
            m = sum(iv) / len(iv)
            cv = (sum((x - m) ** 2 for x in iv) / len(iv)) ** 0.5 / m if m > 0 else 9
            if cv <= 0.25 and m >= 10:
                alerts.append({"kind": "beaconing", "remote": host, "first_s": round(ts[5] - t0, 2),
                               "what": f"the phone contacts {host} every {m:.0f} s"})
    # big background upload per remote (what the phone-side NetWatch would flag)
    up = defaultdict(int)
    for f in flows:
        if f["src"] == PHONE:
            up[f["dst"]] += f["sbytes"]
    for host, b in up.items():
        if b >= 2_000_000:
            alerts.append({"kind": "suspicious_upload", "remote": host, "bytes": b, "what": f"{b / 1e6:.1f} MB sent to {host}"})
    # DNS
    dw = DnsWatch(dm, uc)
    pending = {}
    for p in pk:
        if UDP in p and DNS in p:
            m = parse_dns(raw(p[UDP].payload))
            if not m:
                continue
            if not m[1]:
                pending[m[0]] = float(p.time)
            else:
                for ev in dw.observe(float(p.time) - t0, p[UDP].dst if hasattr(p[UDP], "dst") else PHONE, m[2], m[3] == 3):
                    alerts.append({"kind": ev.kind, "first_s": round(ev.t, 2), "what": ev.detail.replace(PHONE, "the phone")})
    alerts.sort(key=lambda a: a.get("first_s", 0))
    benign = [f for f in flows if f["dport"] == 443 and f["dst"].startswith("142.250.")]
    scan = [f for f in flows if f["src"] == "192.168.1.66"]
    return {
        "pcap": pcap.name, "packets": len(pk), "flows": len(flows), "flagged_flows": len(flagged),
        "flagged_among_normal_https": f"{sum(f['score'] >= fm.threshold for f in benign)}/{len(benign)}",
        "flagged_among_scan_probes": f"{sum(f['score'] >= fm.threshold for f in scan)}/{len(scan)}",
        "flow_model_note": "UNSW-NB15 flow model is reported, not used for alerts: no normal TLS in its training data",
        "alerts": alerts,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap")
    a = ap.parse_args()
    if a.pcap:
        path = Path(a.pcap)
    else:
        out = ROOT / "bench/device"
        out.mkdir(parents=True, exist_ok=True)
        path = out / "netwatch_demo.pcap"
        build_capture(path)
    res = analyse(path)
    print(json.dumps(res, indent=1))
    if not a.pcap:
        (ROOT / "bench/results/netwatch_pcap_demo.json").write_text(json.dumps(res, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
