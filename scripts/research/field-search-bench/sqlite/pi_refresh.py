import sqlite3, time, os, json, threading, statistics, sys, shutil
sys.path.insert(0,'.')
D="/var/tmp/kina-bench/sqlite/"; P=D+"part_index.db"; N=D+"new_pi.db"
res={}
def sz(p): return os.path.getsize(p) if os.path.exists(p) else 0
def free_gb(): s=os.statvfs(D); return s.f_bavail*s.f_frsize/1e9
def guard():
    if free_gb()<4.5: raise SystemExit("free space below limit: %.1f GB"%free_gb())
class Mon(threading.Thread):
    def __init__(s,paths):
        super().__init__(daemon=True); s.paths=paths; s.stop=False; s.peak={}; s.min_free=1e9
    def run(s):
        while not s.stop:
            for p in s.paths: s.peak[p]=max(s.peak.get(p,0),sz(p))
            s.min_free=min(s.min_free,free_gb())
            if free_gb()<4.0: os._exit(3)
            time.sleep(0.25)
Q1=("SELECT rowid,lcsc,value_num,voltage_v,stock_int FROM part_index WHERE family='capacitor' AND value_num BETWEEN 9.9e-6 AND 10.1e-6 "
    "AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v >= 25) AND stock_int > 0 ORDER BY stock_int DESC LIMIT 40")
class Reader(threading.Thread):
    def __init__(s,path,sql,period=0.2):
        super().__init__(daemon=True); s.path=path; s.sql=sql; s.period=period; s.stop=False; s.log=[]
    def run(s):
        c=sqlite3.connect(s.path,timeout=600); t00=time.time()
        while not s.stop:
            t=time.perf_counter(); ts=time.time()-t00
            try: n=len(c.execute(s.sql).fetchall()); err=None
            except Exception as e: n=-1; err=str(e)
            lat=time.perf_counter()-t; s.log.append((ts,lat,n,err))
            time.sleep(max(0,s.period-lat))
    def summary(s):
        l=[x[1] for x in s.log]; 
        gaps=[b[0]-a[0] for a,b in zip(s.log,s.log[1:])]
        return dict(reads=len(l),max_latency_s=max(l),median_latency_s=statistics.median(l),max_gap_between_reads_s=max(gaps) if gaps else None,
                    p95=sorted(l)[int(.95*len(l))-1] if len(l)>1 else None,errors=sum(1 for x in s.log if x[3]),
                    slow=[(round(a,2),round(b,3)) for a,b,_,_ in s.log if b>0.5][:10])
# --- B for part_index: lean new_pi.db
if os.path.exists(N): os.remove(N)
c=sqlite3.connect(P); c.execute("ATTACH ? AS n",(N,))
c.execute("""CREATE TABLE n.part_index(rowid INTEGER PRIMARY KEY, lcsc TEXT, family TEXT, value_num REAL, voltage_v REAL,
 tolerance_pct REAL, power_w REAL, package TEXT, dielectric TEXT, mounting TEXT, technology TEXT, stock_int INTEGER)""")
t=time.time(); c.execute("INSERT INTO n.part_index SELECT * FROM main.part_index"); c.commit(); res["new_pi_build_s"]=time.time()-t
res["new_pi_size"]=sz(N); c.close(); guard()
print("new_pi built",res,flush=True)
c=sqlite3.connect(P,isolation_level=None); c.execute("ATTACH ? AS n",(N,))
base=sz(P); mon=Mon([P,P+"-journal",P+"-wal"]); mon.start()
t=time.time(); c.execute("BEGIN"); c.execute("DELETE FROM main.part_index"); td=time.time()-t
c.execute("INSERT INTO main.part_index SELECT * FROM n.part_index"); ti=time.time()-t-td
t2=time.time(); c.execute("COMMIT"); tc=time.time()-t2
mon.stop=True; time.sleep(.5)
res["B_pi"]=dict(mode="rollback journal",rows=7146764,delete_s=td,insert_s=ti,commit_s=tc,total_s=td+ti+tc,peak_journal=mon.peak.get(P+"-journal"),peak_db=mon.peak.get(P),db_before=base,db_after=sz(P),min_free_gb=mon.min_free)
print(res["B_pi"],flush=True); guard()
c.execute("DETACH n")
# --- C: generation column
c.execute("ALTER TABLE part_index ADD COLUMN generation INTEGER NOT NULL DEFAULT 1")
t=time.time(); c.execute("CREATE INDEX ix_gen ON part_index(generation)"); res["ix_gen_build_s"]=time.time()-t
res["size_after_gen_col_and_index"]=sz(P)
def gen_swap(label,old,new):
    c=sqlite3.connect(P,isolation_level=None); c.execute("ATTACH ? AS n",(N,))
    off=new*16777216
    before=sz(P); mon=Mon([P,P+"-journal",P+"-wal"]); mon.start()
    rd=Reader("file:"+P+"?mode=ro" if False else P,Q1); rd.start(); time.sleep(0.8)
    t0=time.time(); c.execute("BEGIN")
    c.execute(f"INSERT INTO main.part_index(rowid,lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int,generation) SELECT rowid+{off},lcsc,family,value_num,voltage_v,tolerance_pct,power_w,package,dielectric,mounting,technology,stock_int,{new} FROM n.part_index")
    ti=time.time()-t0; t1=time.time()
    c.execute("DELETE FROM main.part_index WHERE generation=?",(old,)); td=time.time()-t1
    t2=time.time(); c.execute("COMMIT"); tc=time.time()-t2; total=time.time()-t0
    time.sleep(1.0); rd.stop=True; rd.join(); mon.stop=True; time.sleep(.4)
    r=dict(label=label,insert_s=ti,delete_s=td,commit_s=tc,total_s=total,db_before=before,db_after=sz(P),peak_db=mon.peak.get(P),peak_journal=mon.peak.get(P+"-journal"),peak_wal=mon.peak.get(P+"-wal"),
           min_free_gb=mon.min_free,reader=rd.summary(),txn_window=(round(0.8,2),round(0.8+total,2)),reader_log_big=[(round(a,2),round(b,3)) for a,b,_,_ in rd.log if b>0.1],
           rows_after=c.execute("select count(*),min(generation),max(generation) from part_index").fetchone())
    c.close(); return r
res["C_rollback"]=gen_swap("rollback journal, default cache",1,2); print(res["C_rollback"],flush=True); guard()
json.dump(res,open(D+"res_pi_refresh_partial.json","w"),indent=1,default=str)
# VACUUM after deletes (rollback mode)
c=sqlite3.connect(P,isolation_level=None)
b=sz(P); fp=c.execute("PRAGMA freelist_count").fetchone()[0]; pc=c.execute("PRAGMA page_count").fetchone()[0]
mon=Mon([P,P+"-journal",P+"-wal",P+"-journal"]); mon.start(); t=time.time(); c.execute("VACUUM"); vt=time.time()-t; mon.stop=True; time.sleep(.4)
res["vacuum_rollback"]=dict(seconds=vt,before=b,after=sz(P),freelist_pages=fp,page_count=pc,peak_extra=mon.peak.get(P+"-journal"),min_free_gb=mon.min_free)
print(res["vacuum_rollback"],flush=True); guard()
# WAL
print(c.execute("PRAGMA journal_mode=WAL").fetchall()); c.close()
res["C_wal"]=gen_swap("WAL, default cache",2,3); print(res["C_wal"],flush=True); guard()
c=sqlite3.connect(P,isolation_level=None)
wal_before=sz(P+"-wal"); t=time.time(); ck=c.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchall(); res["wal_checkpoint"]=dict(seconds=time.time()-t,result=ck,wal_before=wal_before,wal_after=sz(P+"-wal"),db=sz(P))
b=sz(P); t=time.time(); c.execute("VACUUM"); vt=time.time()-t
res["vacuum_wal"]=dict(seconds=vt,before=b,after=sz(P),wal_after=sz(P+"-wal"))
c.execute("PRAGMA wal_checkpoint(TRUNCATE)"); res["vacuum_wal"]["wal_after_ckpt"]=sz(P+"-wal"); res["vacuum_wal"]["after_ckpt"]=sz(P)
print(res["vacuum_wal"],flush=True)
json.dump(res,open(D+"res_pi_refresh.json","w"),indent=1,default=str)
