"""Turn a Guardian session log (files/bench/guardian_<ts>.csv, pulled with adb) into the
battery / thermal / clock numbers of the report.

  adb pull /sdcard/Android/data/io.kavach.guardian/files/bench/ bench/device/
  python bench/parse_session.py bench/device/bench/guardian_<ts>.csv
Prints battery drain per hour of guarding, temperature rise, mean per-core clock, Llama
latency summary, and writes a PNG next to the CSV.
"""
import csv
import statistics
import sys
from pathlib import Path

path = Path(sys.argv[1])
perf, llm = [], []
for r in csv.reader(open(path)):
    if len(r) < 3 or r[0] == "wall_ms":
        continue
    if r[2] == "perf":
        perf.append(r)
    elif r[2] == "llm":
        llm.append(r)
t = [float(r[1]) for r in perf]
batt = [float(r[3]) for r in perf]
temp = [float(r[4]) for r in perf]
hours = (t[-1] - t[0]) / 3600 if len(t) > 1 else 0
print(f"session {hours*60:.1f} min, battery {batt[0]:.0f}% -> {batt[-1]:.0f}% => {(batt[0]-batt[-1])/hours if hours else 0:.1f} %/h")
print(f"battery temperature {temp[0]:.1f} -> {max(temp):.1f} C (peak)")
freqs = [[float(x) for x in r[7].split("|") if x not in ("", "null", "None")] for r in perf if len(r) > 7]
if freqs and freqs[0]:
    print("mean clock per core MHz:", [round(statistics.mean(f[i] for f in freqs if len(f) > i)) for i in range(len(freqs[0]))])
if llm:
    ttft = [float(r[5]) for r in llm]
    print(f"Llama alerts: {len(llm)}, TTFT median {statistics.median(ttft):.0f} ms, decode tok/s median {statistics.median(float(r[7]) for r in llm):.1f}")
try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(2, 1, figsize=(7, 5), sharex=True)
    ax[0].plot([x / 60 for x in t], batt); ax[0].set_ylabel("battery %")
    ax[1].plot([x / 60 for x in t], temp, color="#c62828"); ax[1].set_ylabel("battery °C"); ax[1].set_xlabel("minutes guarding")
    fig.tight_layout(); fig.savefig(path.with_suffix(".png"), dpi=150)
    print("plot:", path.with_suffix(".png"))
except ImportError:
    pass
