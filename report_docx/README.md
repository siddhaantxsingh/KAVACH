# Edit and export the final report

The authoritative editable source is [KAVACH_Phase1_Report.docx](../docs/KAVACH_Phase1_Report.docx). The matching [PDF](../docs/KAVACH_Phase1_Report.pdf) contains 69 pages and includes the final four live-call scripts, outcomes, evidence table and institute title page.

Edit the Word document directly. Export it to PDF with Word or LibreOffice, check the contents page and inspect the rendered pages before replacing the PDF. Preserve the established filenames so repository links continue to work.

```bash
mkdir -p /tmp/kavach-report-export
libreoffice --headless --convert-to pdf --outdir /tmp/kavach-report-export docs/KAVACH_Phase1_Report.docx
```

Run the command from the repository root. The figures embedded in the document also have source copies under `docs/` and `paper/fig/`.
