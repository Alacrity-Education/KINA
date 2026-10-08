import sqlite3, time, os, json
P="/var/tmp/kina-bench/sqlite/part_index.db"
c=sqlite3.connect(P,isolation_level=None)
IDX=[("ix_fam_val","(family, value_num)"),("ix_fam_pkg","(family, package)"),("ix_val","(value_num)"),("ix_volt","(voltage_v)"),("ix_tol","(tolerance_pct)"),("ix_fam_val_stock","(family, value_num) WHERE stock_int > 0")]
res=[dict(name="(table only)",seconds=0,size=os.path.getsize(P),growth=0)]
prev=os.path.getsize(P)
for n,d in IDX:
    t=time.time(); c.execute(f"CREATE INDEX {n} ON part_index {d}"); dt=time.time()-t
    s=os.path.getsize(P); res.append(dict(name=n,def_=d,seconds=dt,size=s,growth=s-prev)); prev=s; print(res[-1],flush=True)
t=time.time(); c.execute("ANALYZE"); res.append(dict(name="ANALYZE",seconds=time.time()-t,size=os.path.getsize(P),growth=os.path.getsize(P)-prev))
print(res[-1])
# per-object sizes
try:
    res.append(dict(dbstat=c.execute("select name,sum(pgsize) from dbstat group by name order by 2 desc").fetchall()))
except Exception as e: print(e)
json.dump(res,open("../res_idx.json","w"),indent=1)
