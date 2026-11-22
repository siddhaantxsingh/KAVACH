# Call-scoring utilities and scenario reference

The completed Phase 1 tests are documented in [the live-call results](../README.md) and [final report](../../docs/KAVACH_Phase1_Report.pdf).

This directory contains reusable [scenario cards](scenario_cards.md), [consent text](consent.md), an [annotation format](annotation_template.csv) and [field definitions](FIELDS.md) for the scoring utility. These reference files are not a results dataset or a submission checklist.

To score a separate annotated dataset, supply its own session table and logs:

```bash
python bench/pilot20_report.py --sessions /path/to/sessions.csv --annot /path/to/annotations.csv --logs /path/to/logs --out /path/to/results.json
```

The tool retains its original filename for compatibility. It derives denominators from the supplied records and does not require exactly 20 calls.
