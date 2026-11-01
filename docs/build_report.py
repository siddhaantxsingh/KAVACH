"""Build docs/KAVACH_Phase1_Technical_Report.pdf from docs/technical_report.md.

1. regenerate docs/figures/call_results.png from bench/results/calls_*.json
2. pandoc markdown -> standalone HTML with docs/report.css
3. headless Chromium (Playwright) -> A4 PDF (correct Devanagari shaping)
Usage: python docs/build_report.py
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

DOCS = Path(__file__).resolve().parent
ROOT = DOCS.parent


def chart() -> None:
    import matplotlib

    matplotlib.use("Agg")
    from matplotlib import font_manager

    for f in Path.home().joinpath(".fonts").glob("NotoSans*.ttf"):
        font_manager.fontManager.addfont(str(f))
    import matplotlib.pyplot as plt

    plt.rcParams.update({"font.family": "Noto Sans", "font.size": 10, "axes.edgecolor": "#c9c8c3", "axes.labelcolor": "#52514e",
                         "xtick.color": "#52514e", "ytick.color": "#52514e"})
    sets = [("dev", "Synthetic dev\n(used for tuning)"), ("test", "Synthetic test\n(unseen templates)"),
            ("handwritten", "Hand-written set 1\n(16 scam / 14 genuine)"), ("heldout", "Locked held-out\n(10 / 10, never tuned)")]
    R = {s: json.loads((ROOT / f"bench/results/calls_{s}.json").read_text()) for s, _ in sets}
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8), sharey=True, facecolor="#fcfcfb")
    for ax, (metric, title) in zip(axes, [("stop_recall", "Scam calls that reach STOP"), ("caught_before_money", "STOP before the money / OTP request")]):
        ax.set_facecolor("#fcfcfb")
        for k, (cfg, col, lab) in enumerate([("rules", "#2a78d6", "Rules only (fail-safe path)"),
                                             ("combined", "#eb6834", "Rules + classifier (shipped, Llama not included)")]):
            vals = [R[s][cfg][metric] * 100 for s, _ in sets]
            pos = [i + (-0.19 if k == 0 else 0.19) for i in range(len(sets))]
            ax.barh(pos, vals, height=0.34, color=col, label=lab, edgecolor="#fcfcfb", linewidth=2)
            for p, v in zip(pos, vals):
                ax.text(v + 1.5, p, f"{v:.0f}%", va="center", fontsize=8.5, color="#0b0b0b")
        ax.set_yticks(range(len(sets)))
        ax.set_yticklabels([l for _, l in sets], fontsize=8.5)
        ax.invert_yaxis()
        ax.set_xlim(0, 112)
        ax.set_title(title, fontsize=10.5, color="#0b0b0b", loc="left")
        ax.grid(axis="x", color="#ecebe7")
        ax.set_axisbelow(True)
        for s in ("top", "right"):
            ax.spines[s].set_visible(False)
        ax.set_xlabel("% of scam calls")
    fig.legend(*axes[0].get_legend_handles_labels(), loc="lower center", ncol=2, frameon=False, fontsize=9)
    fig.tight_layout(rect=(0, 0.08, 1, 1))
    fig.savefig(DOCS / "figures/call_results.png", dpi=200, facecolor="#fcfcfb")


def pdf() -> Path:
    html = DOCS / "technical_report.html"
    subprocess.run(["pandoc", str(DOCS / "technical_report.md"), "-s", "--toc", "--toc-depth=2", "-c", "report.css",
                    "--metadata", "lang=en", "-o", str(html)], check=True, cwd=DOCS)
    out = DOCS / "KAVACH_Phase1_Technical_Report.pdf"
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page()
        page.goto(html.as_uri())
        page.wait_for_load_state("networkidle")
        page.pdf(path=str(out), format="A4", print_background=True, margin={"top": "16mm", "bottom": "16mm", "left": "14mm", "right": "14mm"},
                 display_header_footer=True, header_template="<span></span>",
                 footer_template='<div style="font-size:8px;width:100%;text-align:center;color:#777">KAVACH · Phase 1 technical report · <span class="pageNumber"></span>/<span class="totalPages"></span></div>')
        b.close()
    html.unlink()
    return out


if __name__ == "__main__":
    chart()
    print("wrote", pdf())
