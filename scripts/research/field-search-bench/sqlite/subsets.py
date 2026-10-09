import sqlite3,time,os,json
D="/var/tmp/kina-bench/sqlite/"
SRC="file:/var/tmp/kina-bench/parts-fts5.db?mode=ro"
COLS=['LCSC Part','First Category','Second Category','MFR.Part','Package','Solder Joint','Manufacturer','Library Type','Description','Datasheet','Price','Stock']
sel=",".join('"%s"'%c for c in COLS)
FTS="""CREATE VIRTUAL TABLE parts using fts5 (
        'LCSC Part','First Category','Second Category','MFR.Part','Package','Solder Joint' unindexed,'Manufacturer','Library Type','Description','Datasheet' unindexed,'Price' unindexed,'Stock' unindexed, tokenize="trigram")"""
res={}
for f in ("plain_all.db","plain_subset.db","subset.db","new.db","new2.db"):
    if os.path.exists(D+f): os.remove(D+f)
c=sqlite3.connect(D+"plain_all.db"); c.execute("ATTACH ? AS s",(SRC,)) if False else None
c.close()
c=sqlite3.connect(":memory:",uri=True); c.close()
def attach_src(c):
    c.execute("ATTACH DATABASE ? AS s",(SRC,))
# plain_all: residues 0,1,2 in one scan
c=sqlite3.connect("file:"+D+"plain_all.db",uri=True); attach_src(c)
c.execute("CREATE TABLE parts(src_rowid INTEGER, "+",".join('"%s"'%x for x in COLS)+")")
t=time.time(); c.execute(f"INSERT INTO parts SELECT rowid,{sel} FROM s.parts WHERE rowid % 14 IN (0,1,2)"); c.commit(); res["plain_all_scan_s"]=time.time()-t
res["plain_all_rows"]=c.execute("select count(*) from parts").fetchone()[0]; c.close(); print(res,flush=True)
def build_fts(name,resid):
    c=sqlite3.connect(D+name); c.execute("ATTACH DATABASE ? AS a",(D+"plain_all.db",)); c.execute(FTS)
    t=time.time(); c.execute(f"INSERT INTO parts(rowid,{sel}) SELECT src_rowid,{sel} FROM a.parts WHERE src_rowid % 14 = {resid}"); c.commit(); dt=time.time()-t
    n=c.execute("select count(*) from parts").fetchone()[0]; c.close(); return dict(build_s=dt,rows=n,size=os.path.getsize(D+name))
for name,r in (("subset.db",0),("new.db",1),("new2.db",2)):
    res[name]=build_fts(name,r); print(name,res[name],flush=True)
# plain subset = same rows as subset.db, regular table, all columns as in the source
c=sqlite3.connect(D+"plain_subset.db"); c.execute("ATTACH DATABASE ? AS a",(D+"plain_all.db",))
c.execute("CREATE TABLE parts("+",".join('"%s"'%x for x in COLS)+")")
t=time.time(); c.execute(f"INSERT INTO parts SELECT {sel} FROM a.parts WHERE src_rowid % 14 = 0"); c.commit(); res["plain_subset"]=dict(build_s=time.time()-t,rows=c.execute("select count(*) from parts").fetchone()[0],size=os.path.getsize(D+"plain_subset.db")); c.close()
print(res["plain_subset"])
os.remove(D+"plain_all.db")
for name in ("subset.db","new.db","plain_subset.db"):
    c=sqlite3.connect(D+name); res["dbstat_"+name]=c.execute("select name,sum(pgsize),sum(unused) from dbstat group by name order by 2 desc").fetchall(); c.close()
    print(name,res["dbstat_"+name])
json.dump(res,open(D+"res_subsets.json","w"),indent=1)
