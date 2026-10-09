import sqlite3,time,os,json,sys; sys.path.insert(0,'.')
exec(open('pi_refresh.py').read().split("# --- B for part_index")[0].split("res={}")[0])
D="/var/tmp/kina-bench/sqlite/"; P=D+"part_index.db"; N=D+"new_pi.db"
def sz(p): return os.path.getsize(p) if os.path.exists(p) else 0
import threading, statistics
src=open('pi_refresh.py').read()
ns={}
exec(src[src.index("class Mon"):src.index("# --- B for part_index")],{**globals(),'free_gb':lambda:(os.statvfs(D).f_bavail*os.statvfs(D).f_frsize/1e9),'sz':sz},ns)
Mon,Reader,Q1=ns['Mon'],ns['Reader'],ns['Q1']
c=sqlite3.connect(P,isolation_level=None); c.execute("ATTACH ? AS n",(N,))
idx=[r for r in c.execute("select name,sql from sqlite_master where type='index' and tbl_name='part_index' and sql is not null")]
print(idx)
mon=Mon([P,P+"-wal"]); mon.start(); rd=Reader(P,Q1); rd.start(); time.sleep(.8)
before=sz(P); t0=time.time(); c.execute("BEGIN")
for n,s in idx: c.execute(f"DROP INDEX {n}")
t1=time.time(); c.execute("DELETE FROM main.part_index"); t2=time.time()
c.execute("INSERT INTO main.part_index(rowid,lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int) SELECT rowid,lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int FROM n.part_index"); t3=time.time()
for n,s in idx: c.execute(s)
t4=time.time(); c.execute("COMMIT"); t5=time.time()
time.sleep(1); rd.stop=True; rd.join(); mon.stop=True; time.sleep(.4)
r=dict(drop_idx_s=t1-t0,delete_s=t2-t1,insert_s=t3-t2,recreate_idx_s=t4-t3,commit_s=t5-t4,total_s=t5-t0,db_before=before,db_after=sz(P),peak_wal=mon.peak.get(P+"-wal"),reader=rd.summary(),min_free=mon.min_free)
print(r); 
b=sz(P); c.execute("PRAGMA wal_checkpoint(TRUNCATE)"); c.execute("ANALYZE"); r["db_after_ckpt"]=sz(P)
json.dump(r,open(D+"res_pi_e.json","w"),indent=1,default=str)
