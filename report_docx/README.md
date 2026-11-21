# Build the single KAVACH report

`content1.js`, `content2.js` and `content3.js` are the content source; `build.js` preserves the long report’s typography, chapter structure, tables, figures and annexures. Outputs are exclusively `docs/KAVACH_Phase1_Report.docx` and `docs/KAVACH_Phase1_Report.pdf`.

Requires Node.js with the `docx` package, Python 3, LibreOffice, and Poppler (`pdfinfo`, `pdftotext`). From this directory:

```bash
node build.js pages.json ../docs/KAVACH_Phase1_Report.docx
mkdir -p /tmp/kavach-report-render
libreoffice --headless --convert-to pdf --outdir /tmp/kavach-report-render ../docs/KAVACH_Phase1_Report.docx
python find_pages.py /tmp/kavach-report-render/KAVACH_Phase1_Report.pdf keys.json pages.json
node build.js pages.json ../docs/KAVACH_Phase1_Report.docx
libreoffice --headless --convert-to pdf --outdir /tmp/kavach-report-render ../docs/KAVACH_Phase1_Report.docx
```

Recheck page mappings after the final render; repeat if they changed. Inspect the PDF for table, figure and page-break problems, then copy that final PDF to `../docs/KAVACH_Phase1_Report.pdf`. `keys.json` is generated in the working directory, so run these commands from `report_docx/`.

The figure assets in `paper/fig/` are retained because this report uses them; the superseded IEEE manuscript is no longer an alternative report. Keep synthetic/TTS, playback, live-call observations and device benchmarks labelled by their actual methods.
