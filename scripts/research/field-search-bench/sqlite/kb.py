import sqlite3, time, statistics, re
SRC = "file:/var/tmp/kina-bench/parts-fts5.db?mode=ro"
COLS = '"LCSC Part","First Category","Second Category","MFR.Part",Package,"Solder Joint",Manufacturer,"Library Type",Description,Datasheet,Price,Stock'
IN_STOCK = 'CAST("Stock" AS INTEGER) > 0'

def contains_value(text, token):  # port of JlcpcbSqliteSearch.containsValue
    if not text or not token: return 0
    h = text.lower(); n = token.lower(); frm = 0
    while True:
        at = h.find(n, frm)
        if at < 0: return 0
        if at == 0 or not (h[at-1].isdigit() or h[at-1] == '.'): return 1
        frm = at + 1

def connect(uri=SRC, **kw):
    c = sqlite3.connect(uri, uri=True, check_same_thread=False, **kw)
    c.create_function("kina_value", 2, contains_value, deterministic=True)
    return c

def q(s): return '"' + s.replace('"', '""') + '"'

class T:  # a parsed term, as JlcpcbQuery would produce
    def __init__(s, text, kind, alts=None, column=None):
        s.text, s.kind, s.alts, s.column = text, kind, alts or [], column
    def phrases(s): return s.alts or [s.text]
    def matchable(s): return all(len(p) >= 3 for p in s.phrases())
    def boundary(s): return s.kind in ("VALUE", "POSITIONS", "PITCH")
    def expr(s):
        ph = s.phrases()
        inner = q(ph[0]) if len(ph) == 1 else "(" + " OR ".join(q(p) for p in ph) + ")"
        return inner if not s.column else q(s.column) + " : " + inner
    def __repr__(s): return s.text

DROP = ["KEYWORD","FEATURE","MOUNTING","ORIENTATION","PITCH","DIELECTRIC","PACKAGE","RATING","VALUE","POSITIONS","FAMILY","CATEGORY"]
def drop_rank(t):
    if t.kind == "VALUE" and t.text.endswith("%"): return DROP.index("DIELECTRIC") + .5
    return DROP.index(t.kind)

def conjunction(terms):
    clauses, params = [], []
    m = " AND ".join("(" + t.expr() + ")" for t in terms if t.matchable())
    has = bool(m)
    if has: clauses.append("parts MATCH ?"); params.append(m)
    for t in terms:
        if not t.matchable():
            lk = []
            for p in t.phrases():
                lk.append("\"Description\" LIKE ? ESCAPE '\\'"); params.append("%" + p.replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%")
            clauses.append(lk[0] if len(lk) == 1 else "(" + " OR ".join(lk) + ")")
        if t.kind == "VALUE":
            clauses.append('kina_value("Description", ?)'); params.append(t.text)
        elif t.boundary():
            col = '"Package"' if t.kind == "PITCH" else '"MFR.Part"'
            ch = []
            for p in t.phrases():
                ch.append(f'kina_value("Description", ?) OR kina_value({col}, ?)'); params += [p, p]
            clauses.append("(" + " OR ".join(ch) + ")")
    clauses.append(IN_STOCK)
    return " AND ".join(clauses), params, has

def any_pred(terms):
    m = " OR ".join("(" + t.expr() + ")" for t in terms if t.matchable())
    return "parts MATCH ? AND " + IN_STOCK, [m], True

def count(c, p):
    w, ps, _ = p
    return c.execute(f"SELECT count(*) FROM parts WHERE {w}", ps).fetchone()[0]
def page(c, p, limit=200):
    w, ps, has = p
    order = 'rank, CAST("Stock" AS INTEGER) DESC' if has else 'CAST("Stock" AS INTEGER) DESC'
    return c.execute(f"SELECT {COLS} FROM parts WHERE {w} ORDER BY {order} LIMIT ?", ps + [limit]).fetchall()
def count_nostock(c, p):
    w, ps, h = p
    suf = " AND " + IN_STOCK
    return count(c, (w[:-len(suf)], ps, h))

def search(c, terms, trace=None):
    """Port of JlcpcbSqliteSearch.search: ALL, out-of-stock count, relaxation, PARAMETRIC, ANY. Returns (mode,total,rows,dropped)."""
    def log(s):
        if trace is not None: trace.append(s)
    p = conjunction(terms); has = p[2]
    tot = count(c, p) if p else 0
    if tot > 0:
        log(f"ALL total={tot}"); return "ALL", tot, page(c, p), []
    oos = count_nostock(c, p); log(f"ALL total=0 (out-of-stock matches {oos})")
    tried = {tuple(id(t) for t in terms)}
    remaining = list(terms); dropped = []
    def occurs(t):
        return c.execute("SELECT 1 FROM parts WHERE parts MATCH ? LIMIT 1", [t.expr()]).fetchone() is not None
    dead = [t for t in remaining if t.matchable() and not occurs(t)]
    log(f"dead terms {dead}")
    def attempt(rem, dr, mode="RELAXED"):
        key = tuple(id(t) for t in rem)
        if not rem or key in tried: return None
        tried.add(key)
        pp = conjunction(rem)
        if not pp[2]: return None
        n = count(c, pp); log(f"attempt {rem} -> {n}")
        if n == 0: return None
        return mode, n, page(c, pp), list(dr)
    if dead and len(dead) < len(remaining):
        remaining = [t for t in remaining if t not in dead]; dropped += [t.text for t in dead]
        r = attempt(remaining, dropped)
        if r: return r
    while sum(1 for t in remaining if t.kind != "RATING") > 2:
        best = None; br = 1e9
        for t in remaining:
            rk = drop_rank(t)
            if rk <= br: best, br = t, rk
        remaining.remove(best); dropped.append(best.text)
        r = attempt(remaining, dropped)
        if r: return r
    pp = any_pred(terms)
    n = count(c, pp); log(f"ANY -> {n}")
    if n: return "ANY", n, page(c, pp), []
    return "EMPTY", 0, [], []

# ---- the benchmark queries (what JlcpcbQuery.parse yields for each text)
Q = {
 "Q1 10uF X7R 0805": [T("10uF","VALUE"),T("X7R","DIELECTRIC"),T("0805","PACKAGE")],
 "Q2 4.7k 0603": [T("4.7k","VALUE"),T("0603","PACKAGE")],
 "Q3 Female Header 1x6P Right Angle": [T("Female Header","CATEGORY",["Female Header"],"Second Category"),T("1x6P","POSITIONS"),T("Right Angle","ORIENTATION",["Right Angle"])],
 "Q4 Thin Film 5.36k 0805": [T("Thin Film","KEYWORD"),T("5.36k","VALUE"),T("0805","PACKAGE")],
 "Q5 1k (LIKE path)": [T("1k","VALUE")],
}
Q6 = [T("100nF","VALUE"),T("X7R","DIELECTRIC"),T("0402","PACKAGE"),T("16V","VALUE"),T("capacitor","FAMILY"),T("Murata","KEYWORD")]
QR = [T("10uF","VALUE"),T("X7R","DIELECTRIC"),T("0805","PACKAGE"),T("1%","VALUE"),T("100V","VALUE")]

def timeit(fn, warm=5):
    t = time.perf_counter(); r = fn(); first = time.perf_counter() - t
    ws = []
    for _ in range(warm):
        t = time.perf_counter(); fn(); ws.append(time.perf_counter() - t)
    return first, statistics.median(ws), min(ws), max(ws), r
