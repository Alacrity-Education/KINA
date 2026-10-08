import sqlite3, time, os, json, statistics, sys; sys.path.insert(0,'.')
from kb import contains_value
P="/var/tmp/kina-bench/sqlite/part_index.db"
def evict(p):
    fd=os.open(p,os.O_RDONLY); os.posix_fadvise(fd,0,0,os.POSIX_FADV_DONTNEED); os.close(fd)
def conn():
    c=sqlite3.connect("file:/var/tmp/kina-bench/sqlite/part_index.db?mode=ro",uri=True)
    c.execute("ATTACH 'file:/var/tmp/kina-bench/parts-fts5.db?mode=ro' AS f"); return c
c=conn()
CAP="family='capacitor' AND value_num BETWEEN 9.9e-6 AND 10.1e-6"
RES="family='resistor' AND value_num BETWEEN 4653 AND 4747"
VOLT="(voltage_v IS NULL OR voltage_v >= 25)"
S="stock_int > 0"
Qs={
"Q1 cap 10uF+-1% 0805 X7R >=25V":f"{CAP} AND package='0805' AND dielectric='X7R' AND {VOLT} AND {S}",
"Q2 (no dielectric)":f"{CAP} AND package='0805' AND {VOLT} AND {S}",
"Q3 (no dielectric, no package)":f"{CAP} AND {VOLT} AND {S}",
"Q4 res 4.7k 1% 0603":f"{RES} AND package='0603' AND (tolerance_pct IS NULL OR tolerance_pct <= 1) AND {S}",
}
sqls={}
for k,w in Qs.items():
    sqls[k]=(f"SELECT rowid,lcsc,value_num,voltage_v,stock_int FROM part_index WHERE {w} ORDER BY stock_int DESC LIMIT 40",
             f"SELECT count(*) FROM part_index WHERE {w}")
PI="pi.family='resistor' AND pi.value_num BETWEEN 4653 AND 4747 AND pi.package='0805' AND pi.stock_int > 0"
sqls["Q5 res 4.7k 0805 + FTS 'thin film'"]=(
 f"SELECT pi.rowid,pi.lcsc,pi.value_num,pi.stock_int FROM part_index pi JOIN f.parts AS fp ON fp.rowid=pi.rowid WHERE fp.parts MATCH '\"thin film\"' AND {PI} ORDER BY pi.stock_int DESC LIMIT 40",
 f"SELECT count(*) FROM part_index pi JOIN f.parts AS fp ON fp.rowid=pi.rowid WHERE fp.parts MATCH '\"thin film\"' AND {PI}")
for key,base in (("Q1 cap 10uF+-1% 0805 X7R >=25V","Q1y"),("Q4 res 4.7k 1% 0603","Q4y")):
    sqls[base+" same, INDEXED BY ix_fam_val_stock"]=tuple(x.replace("FROM part_index","FROM part_index INDEXED BY ix_fam_val_stock") for x in sqls[key])
sqls["Q1x same as Q1 NOT INDEXED (full scan)"]=(sqls["Q1 cap 10uF+-1% 0805 X7R >=25V"][0].replace("FROM part_index","FROM part_index NOT INDEXED"),sqls["Q1 cap 10uF+-1% 0805 X7R >=25V"][1].replace("FROM part_index","FROM part_index NOT INDEXED"))
res={}
for k,(sel,cnt) in sqls.items():
    r={"sql":sel,"count_sql":cnt}
    r["plan"]=[x[3] for x in c.execute("EXPLAIN QUERY PLAN "+sel)]
    r["count_plan"]=[x[3] for x in c.execute("EXPLAIN QUERY PLAN "+cnt)]
    for nm,sql in (("select",sel),("count",cnt)):
        c2=conn(); evict(P)
        if k.startswith("Q5"): evict("/var/tmp/kina-bench/parts-fts5.db")
        t=time.perf_counter(); rows=c2.execute(sql).fetchall(); first=time.perf_counter()-t
        ws=[]
        for _ in range(7):
            t=time.perf_counter(); c2.execute(sql).fetchall(); ws.append(time.perf_counter()-t)
        r[nm]=dict(first_s=first,warm_median_s=statistics.median(ws),warm_min=min(ws),warm_max=max(ws),rows=len(rows) if nm=="select" else rows[0][0])
    res[k]=r; print(k,{a:r[a] for a in("select","count","plan","count_plan")},flush=True)
json.dump(res,open("../res_fq.json","w"),indent=1)
