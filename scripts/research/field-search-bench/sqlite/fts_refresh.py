import sqlite3,time,os,json,sys,threading,statistics
sys.path.insert(0,'.')
src=open('pi_refresh.py').read()
D="/var/tmp/kina-bench/sqlite/"
def sz(p): return os.path.getsize(p) if os.path.exists(p) else 0
def free_gb(): s=os.statvfs(D); return s.f_bavail*s.f_frsize/1e9
ns={}; exec(src[src.index("class Mon"):src.index("Q1=(")]+src[src.index("class Reader"):src.index("# --- B for part_index")],{'threading':threading,'time':time,'os':os,'sqlite3':sqlite3,'statistics':statistics,'free_gb':free_gb,'sz':sz},ns)
Mon,Reader=ns['Mon'],ns['Reader']
P=D+"subset.db"
RQ="""SELECT "LCSC Part",Description FROM parts WHERE parts MATCH '("10uF") AND ("X7R") AND ("0805")' AND CAST("Stock" AS INTEGER) > 0 ORDER BY rank, CAST("Stock" AS INTEGER) DESC LIMIT 200"""
FACTOR=7146764/510483
res={"factor":FACTOR}
def run(label,newdb,mode,variant):
    c=sqlite3.connect(P,isolation_level=None)
    c.execute(f"PRAGMA journal_mode={mode}"); c.execute("ATTACH ? AS n",(D+newdb,))
    before=sz(P); mon=Mon([P,P+"-journal",P+"-wal"]); mon.start(); rd=Reader(P,RQ); rd.start(); time.sleep(.8)
    t0=time.time(); c.execute("BEGIN"); err=None
    t1=t0
    if variant=="delete":
        c.execute("DELETE FROM main.parts"); t1=time.time()
        c.execute("INSERT INTO main.parts SELECT * FROM n.parts"); t2=time.time()
    elif variant=="delete-all":
        c.execute("INSERT INTO main.parts(parts) VALUES('delete-all')"); t1=time.time()
        c.execute("INSERT INTO main.parts SELECT * FROM n.parts"); t2=time.time()
    elif variant=="drop-create":
        c.execute("DROP TABLE main.parts")
        c.execute(open('subsets.py').read().split('FTS="""')[1].split('"""')[0].join(["CREATE VIRTUAL TABLE main.parts using fts5 (","" ]) if False else None) if False else None
        c.execute("""CREATE VIRTUAL TABLE main.parts using fts5 ('LCSC Part','First Category','Second Category','MFR.Part','Package','Solder Joint' unindexed,'Manufacturer','Library Type','Description','Datasheet' unindexed,'Price' unindexed,'Stock' unindexed, tokenize="trigram")"""); t1=time.time()
        c.execute("INSERT INTO main.parts SELECT * FROM n.parts"); t2=time.time()
    c.execute("COMMIT"); t3=time.time()
    time.sleep(1); rd.stop=True; rd.join(); mon.stop=True; time.sleep(.4)
    n=c.execute("select count(*) from parts").fetchone()[0]
    r=dict(label=label,variant=variant,mode=mode,delete_s=t1-t0,insert_s=t2-t1,commit_s=t3-t2,total_s=t3-t0,extrapolated_total_s=(t3-t0)*FACTOR,
           rows_after=n,db_before=before,db_after=sz(P),peak_journal=mon.peak.get(P+"-journal"),peak_wal=mon.peak.get(P+"-wal"),min_free_gb=mon.min_free,reader=rd.summary())
    if mode=="WAL": c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    c.close(); print(r,flush=True); return r
for label,newdb,mode,variant in (("rollback, DELETE+INSERT","new.db","DELETE","delete"),("WAL, DELETE+INSERT","new2.db","WAL","delete"),
        ("WAL, delete-all+INSERT","new.db","WAL","delete-all"),("WAL, DROP+CREATE+INSERT","new2.db","WAL","drop-create"),("rollback, DROP+CREATE+INSERT","new.db","DELETE","drop-create")):
    try: res[label]=run(label,newdb,mode,variant)
    except Exception as e: res[label]=dict(error=str(e)); print(label,"ERROR",e,flush=True)
    try: c=sqlite3.connect(P); print(c.execute("pragma integrity_check").fetchone()[0] if False else "", end=""); c.close()
    except Exception as e: print(e)
# VACUUM on the subset for reference
c=sqlite3.connect(P,isolation_level=None); c.execute("PRAGMA journal_mode=DELETE"); b=sz(P); t=time.time(); c.execute("VACUUM"); res["vacuum_subset"]=dict(seconds=time.time()-t,before=b,after=sz(P))
print(res["vacuum_subset"])
json.dump(res,open(D+"res_fts_refresh.json","w"),indent=1,default=str)
