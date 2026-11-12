// Build the KAVACH Phase 1 report (.docx). Usage: node build.js pages.json out.docx
const fs = require('fs');
const { execFileSync } = require('child_process');
const D = require('docx');
const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, ImageRun, AlignmentType,
  HeadingLevel, WidthType, BorderStyle, ShadingType, LevelFormat, PageNumber, NumberFormat, Footer, Header,
  PositionalTab, PositionalTabAlignment, PositionalTabRelativeTo, PositionalTabLeader, TabStopType, VerticalAlign } = D;

const c1 = require('./content1.js'), c2 = require('./content2.js'), c3 = require('./content3.js');
const chapters = [...c1.chapters, ...c2, ...c3.chapters];
const pages = fs.existsSync(process.argv[2] || '') ? JSON.parse(fs.readFileSync(process.argv[2])) : {};
const OUT = process.argv[3] || 'KAVACH_Phase1_Report.docx';
const pg = k => (pages[k] !== undefined ? String(pages[k]) : '00');

const FONT = 'Times New Roman';
const BODY = 24; // 12 pt
const R = (text, o = {}) => new TextRun({ text, font: FONT, size: o.size || BODY, bold: o.bold, italics: o.italics, break: o.break, color: o.color });
const para = (children, o = {}) => new Paragraph({ children, alignment: o.align || AlignmentType.JUSTIFIED,
  spacing: { before: o.before || 0, after: o.after === undefined ? 140 : o.after, line: o.line || 360 },
  keepNext: o.keepNext, keepLines: o.keepLines, pageBreakBefore: o.pageBreakBefore, heading: o.heading,
  indent: o.indent, shading: o.shading, border: o.border });

// ---------- numbering (one instance per list so numbered lists restart)
const numConfigs = [{ reference: 'bul', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
  style: { paragraph: { indent: { left: 540, hanging: 300 } } } }] }];
let numCount = 0;
function newNumbered() {
  const ref = 'num' + (numCount++);
  numConfigs.push({ reference: ref, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
    style: { paragraph: { indent: { left: 540, hanging: 360 } } } }] });
  return ref;
}

// ---------- tables
const border = { style: BorderStyle.SINGLE, size: 4, color: '808080' };
const borders = { top: border, bottom: border, left: border, right: border };
function cell(text, w, o = {}) {
  return new TableCell({ width: { size: w, type: WidthType.DXA }, borders, verticalAlign: VerticalAlign.CENTER,
    shading: o.head ? { fill: 'D9E2F3', type: ShadingType.CLEAR, color: 'auto' } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({ alignment: o.head ? AlignmentType.CENTER : AlignmentType.LEFT, spacing: { before: 0, after: 0, line: 260 },
      children: [R(text, { size: 21, bold: o.head })] })] });
}
function makeTable(t) {
  const total = t.widths.reduce((a, b) => a + b, 0);
  const rows = [new TableRow({ tableHeader: true, cantSplit: true, children: t.header.map((h, i) => cell(h, t.widths[i], { head: true })) })];
  for (const r of t.rows) rows.push(new TableRow({ cantSplit: true, children: r.map((x, i) => cell(x, t.widths[i])) }));
  return new Table({ width: { size: total, type: WidthType.DXA }, columnWidths: t.widths, alignment: AlignmentType.CENTER, rows });
}

// ---------- images
function imgSize(path) {
  const out = execFileSync('python3', ['-c', 'import sys;from PIL import Image;print(*Image.open(sys.argv[1]).size)', path]).toString().trim().split(' ');
  return [+out[0], +out[1]];
}
function makeImage(f) {
  const [W, H] = imgSize(f.path);
  let w = f.w * 96, h = w * H / W;
  const maxH = 8.0 * 96; if (h > maxH) { w = w * maxH / h; h = maxH; }
  const type = f.path.endsWith('.png') ? 'png' : 'jpg';
  return new Paragraph({ alignment: AlignmentType.CENTER, keepNext: true, spacing: { before: 120, after: 60 },
    children: [new ImageRun({ type, data: fs.readFileSync(f.path), transformation: { width: Math.round(w), height: Math.round(h) },
      altText: { title: f.caption, description: f.caption, name: 'fig' } })] });
}

// ---------- body blocks
const lot = [], lof = [], toc = [];
function renderBlocks(blocks, chNo, prefix) {
  const out = []; let s = 0, ss = 0, t = 0, f = 0;
  for (const b of blocks) {
    if (b.h2) { s++; ss = 0; const num = `${prefix}${s}`; toc.push({ level: 2, text: `${num} ${b.h2}`, key: `s:${num} ${b.h2}` });
      out.push(para([R(`${num}  ${b.h2}`, { bold: true, italics: true, size: 26 })], { heading: HeadingLevel.HEADING_2, before: 240, after: 120, keepNext: true, align: AlignmentType.LEFT })); }
    else if (b.h3) { ss++; const num = `${prefix}${s}.${ss}`;
      out.push(para([R(`${num}  ${b.h3}`, { italics: true, bold: true })], { heading: HeadingLevel.HEADING_3, before: 180, after: 100, keepNext: true, align: AlignmentType.LEFT })); }
    else if (b.p !== undefined) out.push(para([R(b.p, { bold: b.bold })], { keepNext: b.bold, align: b.left ? AlignmentType.LEFT : undefined }));
    else if (b.pb) out.push(para(b.pb.map(([x, bo]) => R(x, { bold: bo }))));
    else if (b.eq) out.push(para([R(b.eq, { italics: true })], { align: AlignmentType.CENTER, before: 60, after: 160 }));
    else if (b.bullets) for (const x of b.bullets) out.push(new Paragraph({ numbering: { reference: 'bul', level: 0 }, alignment: AlignmentType.JUSTIFIED,
      spacing: { after: 80, line: 340 }, children: [R(x)] }));
    else if (b.numbered) { const ref = newNumbered(); for (const x of b.numbered) out.push(new Paragraph({ numbering: { reference: ref, level: 0 },
      alignment: AlignmentType.JUSTIFIED, spacing: { after: 80, line: 340 }, children: [R(x)] })); }
    else if (b.code) { b.code.forEach((x, i) => out.push(new Paragraph({ spacing: { before: i ? 0 : 80, after: i === b.code.length - 1 ? 160 : 0, line: 260 },
      shading: { fill: 'F2F2F2', type: ShadingType.CLEAR, color: 'auto' }, indent: { left: 200, right: 200 }, keepLines: true, keepNext: i < b.code.length - 1,
      children: [new TextRun({ text: x || ' ', font: 'Courier New', size: 18 })] }))); }
    else if (b.table) { t++; const cap = `Table ${prefix}${t}: ${b.table.caption}`; lot.push({ text: cap, key: `t:Table ${prefix}${t}:` });
      out.push(para([R(cap, { bold: true, size: 22 })], { align: AlignmentType.CENTER, before: 160, after: 80, keepNext: true }));
      out.push(makeTable(b.table)); out.push(para([R('')], { after: 60, line: 240 })); }
    else if (b.fig) { f++; const cap = `Figure ${prefix}${f}: ${b.fig.caption}`; lof.push({ text: cap, key: `f:Figure ${prefix}${f}:` });
      out.push(makeImage(b.fig)); out.push(para([R(cap, { bold: true, size: 22 })], { align: AlignmentType.CENTER, after: 200 })); }
    else throw new Error('unknown block ' + JSON.stringify(b).slice(0, 80));
  }
  return out;
}
function chapterHead(label, title, key) {
  toc.push({ level: 1, text: `${label}  ${title}`, key });
  return [new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, pageBreakBefore: true, spacing: { before: 0, after: 360, line: 360 },
    children: [R(label, { bold: true, size: 32 }), R(title, { bold: true, size: 32, break: 1 })] })];
}

const body = [];
chapters.forEach((ch, i) => { const n = i + 1; body.push(...chapterHead(`CHAPTER ${n}`, ch.title, `c:CHAPTER ${n}`)); body.push(...renderBlocks(ch.blocks, n, `${n}.`)); });
// references
toc.push({ level: 1, text: 'REFERENCES', key: 'c:REFERENCES' });
body.push(new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, pageBreakBefore: true, spacing: { after: 360 }, children: [R('REFERENCES', { bold: true, size: 32 })] }));
c3.references.forEach((r, i) => body.push(new Paragraph({ alignment: AlignmentType.LEFT, spacing: { after: 100, line: 300 }, indent: { left: 560, hanging: 560 },
  tabStops: [{ type: TabStopType.LEFT, position: 560 }], children: [R(`[${i + 1}]\t${r}`, { size: 22 })] })));
// annexures
c3.annexures.forEach((a, i) => { const L = String.fromCharCode(65 + i); toc.push({ level: 1, text: a.title, key: `c:ANNEXURE ${L}` });
  body.push(new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, pageBreakBefore: true, spacing: { after: 360 }, children: [R(a.title, { bold: true, size: 28 })] }));
  body.push(...renderBlocks(a.blocks, 0, `${L}.`)); });

// ---------- front matter
const center = (t, o = {}) => para([R(t, o)], { align: AlignmentType.CENTER, after: o.after === undefined ? 120 : o.after, line: 300 });
const team = [['Siddhant Singh', 'Call listening, speech recognition and the Android app'], ['Bogavalli Sai Akhilash', 'Detection, scam playbook and Llama fine-tuning'], ['Shivansh Bose', 'Shield, Network Watch and security review']];
const noB = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
const teamTable = new Table({ width: { size: 7600, type: WidthType.DXA }, columnWidths: [3200, 4400], alignment: AlignmentType.CENTER,
  rows: team.map(([n, a]) => new TableRow({ children: [n, a].map((x, i) => new TableCell({ width: { size: i ? 4400 : 3200, type: WidthType.DXA },
    borders: { top: noB, bottom: noB, left: noB, right: noB }, margins: { top: 40, bottom: 40, left: 80, right: 80 },
    children: [new Paragraph({ alignment: i ? AlignmentType.LEFT : AlignmentType.LEFT, spacing: { after: 0 }, children: [R(x, { bold: !i, size: i ? 22 : 24, italics: !!i })] })] })) })) });
const rule = new Paragraph({ border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: '1F3864', space: 1 } }, spacing: { after: 240 }, children: [] });
const cover = [
  center('ARM BHARAT AI-SoC CHALLENGE 2026–27', { bold: true, size: 36, after: 120 }),
  center('Problem Statement 3: Cybersecurity and Digital Safety', { size: 26, after: 200 }), rule,
  center('KAVACH', { bold: true, size: 56, after: 120, color: '1F3864' }),
  center('An On-Device, Multilingual Scam-Call Shield for Indian Phone Users', { bold: true, size: 30, after: 60 }),
  center('with Llama 3.2 1B on Arm CPUs', { bold: true, size: 30, after: 1000 }),
  center('A Phase 1 Technical Report', { italics: true, size: 26, after: 60 }),
  center('submitted to the Arm Bharat AI-SoC Challenge 2026–27', { italics: true, size: 24, after: 1400 }),
  center('Submitted by', { bold: true, size: 26, after: 160 }), ...team.map(([n]) => center(n, { bold: true, size: 26, after: 80 })),
  para([R('')], { after: 1000 }),
  center('Under the guidance of', { bold: true, size: 26, after: 100 }),
  center('Dr. Rahul Ratnakumar', { bold: true, size: 26, after: 40 }), center('Faculty Mentor', { italics: true, after: 2000 }),
  center('NOVEMBER 2026', { bold: true, size: 26 })];

const fmHead = (t, key) => { toc.unshift; return new Paragraph({ heading: HeadingLevel.HEADING_1, alignment: AlignmentType.CENTER, pageBreakBefore: true,
  spacing: { after: 360 }, children: [R(t, { bold: true, size: 32 })] }); };
const tocLine = (text, key, o = {}) => new Paragraph({ spacing: { before: o.before || 0, after: o.after || 60, line: 280 }, indent: { left: o.indent || 0, right: 500 },
  tabStops: [{ type: TabStopType.RIGHT, position: 9000, leader: 'dot' }],
  children: [R(text, { bold: o.bold, size: o.size || 24 }), R('\t' + pg(key), { bold: o.bold, size: o.size || 24 })] });

const front = [];
front.push(fmHead('ABSTRACT'));
c1.abstract.forEach((p, i) => front.push(para([R(p, { italics: i === c1.abstract.length - 1 })])));
front.push(fmHead('CONTENTS'));
front.push(tocLine('ABSTRACT', 'fm:ABSTRACT', { bold: true }));
front.push(tocLine('LIST OF TABLES', 'fm:LIST OF TABLES', { bold: true }));
front.push(tocLine('LIST OF FIGURES', 'fm:LIST OF FIGURES', { bold: true }));
front.push(tocLine('LIST OF ABBREVIATIONS', 'fm:LIST OF ABBREVIATIONS', { bold: true }));
for (const e of toc) front.push(e.level === 1 ? tocLine(e.text, e.key, { bold: true, before: 120 }) : tocLine(e.text, e.key, { indent: 500, size: 23 }));
front.push(fmHead('LIST OF TABLES'));
for (const e of lot) front.push(tocLine(e.text, e.key, { size: 22, after: 40 }));
front.push(fmHead('LIST OF FIGURES'));
for (const e of lof) front.push(tocLine(e.text, e.key, { size: 22, after: 40 }));
front.push(fmHead('LIST OF ABBREVIATIONS'));
front.push(makeTable({ widths: [2400, 6600], header: ['Abbreviation', 'Meaning'], rows: c1.abbreviations }));

// keys for the page finder
fs.writeFileSync('keys.json', JSON.stringify({ fm: ['ABSTRACT', 'CONTENTS', 'LIST OF TABLES', 'LIST OF FIGURES', 'LIST OF ABBREVIATIONS'],
  toc: toc.map(e => e.key), lot: lot.map(e => e.key), lof: lof.map(e => e.key) }, null, 1));

// ---------- document
const footer = new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ children: [PageNumber.CURRENT], font: FONT, size: 22 })] })] });
const header = new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: '999999', space: 1 } },
  children: [R('KAVACH – Arm Bharat AI-SoC Challenge 2026–27', { italics: true, size: 18, color: '555555' })] })] });
const pageProps = { size: { width: 11906, height: 16838 }, margin: { top: 1440, bottom: 1300, left: 1700, right: 1200, header: 600, footer: 600 } };
const doc = new Document({
  creator: 'KAVACH team', title: 'KAVACH Phase 1 Technical Report',
  styles: { default: { document: { run: { font: FONT, size: BODY } } },
    paragraphStyles: [
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { font: FONT, size: 32, bold: true, color: '000000' }, paragraph: { spacing: { before: 0, after: 360 }, keepNext: true, outlineLevel: 0 } },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { font: FONT, size: 26, bold: true, italics: true, color: '000000' }, paragraph: { spacing: { before: 240, after: 120 }, keepNext: true, outlineLevel: 1 } },
      { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { font: FONT, size: 24, bold: true, italics: true, color: '000000' }, paragraph: { spacing: { before: 180, after: 100 }, keepNext: true, outlineLevel: 2 } }] },
  numbering: { config: numConfigs },
  sections: [
    { properties: { page: { ...pageProps, pageNumbers: { start: 1, formatType: NumberFormat.LOWER_ROMAN } }, titlePage: true },
      footers: { default: footer, first: new Footer({ children: [new Paragraph({ children: [] })] }) }, children: [...cover, ...front] },
    { properties: { page: { ...pageProps, pageNumbers: { start: 1, formatType: NumberFormat.DECIMAL } } }, headers: { default: header }, footers: { default: footer }, children: body }]
});
Packer.toBuffer(doc).then(b => { fs.writeFileSync(OUT, b); console.log('wrote', OUT, b.length, 'bytes;', lot.length, 'tables', lof.length, 'figures', toc.length, 'toc'); });
