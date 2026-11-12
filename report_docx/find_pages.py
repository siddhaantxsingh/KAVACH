"""Find printed page numbers of headings/captions in the rendered PDF. Usage: find_pages.py report.pdf keys.json pages.json"""
import json, re, subprocess, sys

pdf, keys, out = sys.argv[1:4]
K = json.load(open(keys))
n = int(re.search(r"Pages:\s+(\d+)", subprocess.run(["pdfinfo", pdf], capture_output=True, text=True).stdout).group(1))
texts = []
for i in range(1, n + 1):
    t = subprocess.run(["pdftotext", "-f", str(i), "-l", str(i), "-layout", pdf, "-"], capture_output=True, text=True).stdout
    texts.append(re.sub(r"[ \t]+", " ", t))

def roman(k):
    vals = [(10, "x"), (9, "ix"), (5, "v"), (4, "iv"), (1, "i")]
    s = ""
    for v, r in vals:
        while k >= v:
            s += r; k -= v
    return s

def lines(i):
    return [l.strip() for l in texts[i].splitlines() if l.strip()]

# first chapter page = first page whose first body line (after header) is CHAPTER 1
ch1 = next(i for i in range(n) if any(l == "CHAPTER 1" for l in lines(i)[:3]) and "CONTENTS" not in texts[i])
pages = {}
for f in K["fm"]:
    i = next(i for i in range(ch1) if f in lines(i)[:2])
    pages["fm:" + f] = roman(i + 1)

def find(prefix, start, mode):
    for i in range(start, n):
        for l in lines(i):
            if mode == "line" and l == prefix:
                return i
            if mode == "start" and l.startswith(prefix):
                return i
    return None

cur = ch1
for k in K["toc"]:
    kind, text = k.split(":", 1)
    if kind == "c":
        i = find(text, cur, "start")
    else:  # section "3.2 Title" rendered as "3.2  Title"
        num, title = text.split(" ", 1)
        i = None
        for j in range(cur, n):
            if any(re.match(re.escape(num) + r"\s+" + re.escape(title[:25]), l) for l in lines(j)):
                i = j; break
    if i is None:
        print("NOT FOUND", k); continue
    cur = i
    pages[k] = i - ch1 + 1
for group in ("lot", "lof"):
    cur = ch1
    for k in K[group]:
        text = k.split(":", 1)[1]
        i = find(text, cur, "start")
        if i is None:
            # caption may wrap or be centred with text before; search anywhere
            i = next((j for j in range(cur, n) if text in texts[j]), None)
        if i is None:
            print("NOT FOUND", k); continue
        cur = i
        pages[k] = i - ch1 + 1
json.dump(pages, open(out, "w"), indent=1)
print(n, "pages; chapter 1 at physical page", ch1 + 1, ";", len(pages), "entries")
