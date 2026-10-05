"""Inserts out/report_tables.md into the report placeholders (RESULTS_TABLE, CATEGORY_TABLE). Idempotent: the tables
live between <!-- BEGIN x --> / <!-- END x --> markers after the first run.

    python3 scripts/research/fill_report.py
"""
import os
import re

from common import OUT, ROOT

REPORT = os.path.join(ROOT, "docs", "research", "ranking-evaluation-2026-10-05.md")


def main():
    tables = open(os.path.join(OUT, "report_tables.md")).read().strip().split("\n\n")
    main_t, cat_t = tables[0], tables[1]
    s = open(REPORT).read()
    for name, body in (("RESULTS_TABLE", main_t), ("CATEGORY_TABLE", cat_t)):
        block = "<!-- BEGIN %s -->\n%s\n<!-- END %s -->" % (name, body, name)
        if name in s and "<!-- BEGIN %s -->" % name not in s:
            s = s.replace(name, block, 1)
        else:
            s = re.sub(r"<!-- BEGIN %s -->.*?<!-- END %s -->" % (name, name), lambda m: block, s, flags=re.S)
    open(REPORT, "w").write(s)
    print("report tables updated")


if __name__ == "__main__":
    main()
