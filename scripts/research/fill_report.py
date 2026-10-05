"""Copies the tables of out/report_tables.md into the report: every `<!-- TABLE NAME -->` block of report_tables.md
replaces the text between `<!-- BEGIN NAME -->` and `<!-- END NAME -->` in the report. Idempotent; a marker pair with
no table, or a table with no marker pair, is reported.

    python3 scripts/research/fill_report.py
"""
import os
import re

from common import OUT, ROOT

REPORT = os.path.join(ROOT, "docs", "research", "ranking-evaluation-2026-10-05.md")


def main():
    text = open(os.path.join(OUT, "report_tables.md"), encoding="utf-8").read()
    tables = {m.group(1): m.group(2).strip() for m in
              re.finditer(r"<!-- TABLE (\w+) -->\n(.*?)(?=\n<!-- TABLE |\Z)", text, flags=re.S)}
    s = open(REPORT, encoding="utf-8").read()
    used = []
    for name, body in tables.items():
        pat = r"<!-- BEGIN %s -->.*?<!-- END %s -->" % (name, name)
        if not re.search(pat, s, flags=re.S):
            print("no markers for", name)
            continue
        s = re.sub(pat, lambda _: "<!-- BEGIN %s -->\n%s\n<!-- END %s -->" % (name, body, name), s, flags=re.S)
        used.append(name)
    for name in re.findall(r"<!-- BEGIN (\w+) -->", s):
        if name not in tables:
            print("no table for markers", name)
    open(REPORT, "w", encoding="utf-8").write(s)
    print("report tables updated:", ", ".join(used))


if __name__ == "__main__":
    main()
