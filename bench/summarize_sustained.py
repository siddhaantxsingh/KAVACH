"""Summarize the recorded whole-phone gauge/thermal runs without energy extrapolation."""
import csv
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    out={}
    for name in ('idle','listening'):
        p=ROOT/f'bench/results/device_{name}.csv'
        with p.open() as f: rows=list(csv.DictReader(f))
        temps=[float(r['battery_temp_c']) for r in rows]
        out[name]={'source':str(p.relative_to(ROOT)), 'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
            'samples':len(rows), 'first_t_s':float(rows[0]['t_s']), 'last_t_s':float(rows[-1]['t_s']),
            'observed_span_s':float(rows[-1]['t_s'])-float(rows[0]['t_s']),
            'battery_start_pct':int(rows[0]['battery_pct']), 'battery_end_pct':int(rows[-1]['battery_pct']),
            'battery_drop_percentage_points':int(rows[0]['battery_pct'])-int(rows[-1]['battery_pct']),
            'temperature_start_c':temps[0], 'temperature_end_c':temps[-1], 'temperature_max_c':max(temps),
            'thermal_status_values':sorted({r['thermal_status'] for r in rows}),
            'current_samples':sum(bool(r['current_ua']) for r in rows)}
    out['limitations']=['Whole-phone battery gauge, not app-only current or energy.',
        'Different initial battery levels and temperatures; no justified idle subtraction.',
        'Charging state, screen brightness, workload continuity and build not encoded in CSVs.',
        'Thermal status zero is not a direct proof of no CPU throttling.']
    target=ROOT/'bench/results/sustained_summary.json'
    target.write_text(json.dumps(out,indent=2)+'\n')
    print(target)

if __name__=='__main__':main()
