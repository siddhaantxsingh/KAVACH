"""Summarise bench/results/device_*_llama.csv (median over runs) into a markdown table."""
import csv
import statistics
import sys
from collections import defaultdict

rows = list(csv.DictReader(open(sys.argv[1])))
g = defaultdict(list)
for r in rows:
    g[(r["kleidi"], r["placement"], r["threads"])].append(r)
print("| KleidiAI | cores | threads | TTFT ms | prefill tok/s | decode tok/s |\n|---|---|---|---|---|---|")
for (k, p, t), rs in sorted(g.items()):
    med = lambda f: statistics.median(float(x[f]) for x in rs if x[f])
    print(f"| {k} | {p} | {t} | {med('ttft_ms'):.0f} | {med('prefill_tps'):.1f} | {med('decode_tps'):.2f} |")
