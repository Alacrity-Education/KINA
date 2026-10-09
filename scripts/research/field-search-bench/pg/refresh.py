import psycopg, subprocess, threading, time, json, sys, os, statistics, glob
mode=sys.argv[1]
DSN=os.environ["PGBENCH_DSN"]
c=psycopg.connect(DSN,autocommit=True)
COLS="distributor,part_number,family,lcsc,mpn,manufacturer,description,category1,category2,package,value_num,voltage_v,tolerance_pct,power_w,dielectric,mounting,technology,stock_int,price_text,attributes"
IDX=[("idx_fam_value","(family, value_num)",""),("idx_fam_package","(family, package)",""),("idx_value","(value_num)",""),("idx_voltage","(voltage_v)",""),("idx_tolerance","(tolerance_pct)",""),
("idx_value_cap","(value_num)"," WHERE family = 'capacitor'"),("idx_value_res","(value_num)"," WHERE family = 'resistor'"),
("idx_attrs_gin","USING gin (attributes jsonb_path_ops)",""),("idx_desc_fts","USING gin (to_tsvector('simple', description))",""),
("idx_desc_trgm","USING gin (description gin_trgm_ops)",""),("idx_mpn_trgm","USING gin (mpn gin_trgm_ops)",""),("idx_fam_value_instock","(family, value_num)"," WHERE stock_int > 0")]
def ddl(tbl,suf): return [f"CREATE INDEX {n}{suf} ON {tbl} {d}{w}" for n,d,w in IDX]
steps=[]
def step(name,sql):
    t=time.perf_counter(); c.execute(sql); dt=time.perf_counter()-t
    steps.append((name,round(dt,2))); print(name,round(dt,2),flush=True)
def copy_in(tbl,cols=COLS):
    step('copy '+tbl,f"COPY {tbl} ({cols}) FROM PROGRAM 'cat /data/chunk_*.csv' WITH (FORMAT csv)")
def build(tbl,suf,pk=True):
    if pk: step('pk '+tbl,f"ALTER TABLE {tbl} ADD PRIMARY KEY (distributor, part_number)")
    for s in ddl(tbl,suf): step(s.split()[2],s)
def du():
    return int(subprocess.run(['docker','exec','kina-pgbench','du','-sm','/var/lib/postgresql/data'],capture_output=True,text=True).stdout.split()[0])
peak=[0];base=du();run=[True]
def samp():
    while run[0]:
        peak[0]=max(peak[0],du()); time.sleep(0.5)
def start_reader(tag):
    if os.path.exists('stop'): os.remove('stop')
    return subprocess.Popen([sys.executable,'reader.py',f'reader_{tag}.jsonl'])
def finish(tag,rp,t0,t1,extra=None):
    time.sleep(1); open('stop','w').close(); rp.wait(); os.remove('stop'); run[0]=False
    recs=[json.loads(l) for l in open(f'reader_{tag}.jsonl')]
    ok=[r for r in recs if 'err' not in r]
    ms=[r['ms'] for r in ok]; ts=[r['t'] for r in recs]
    gaps=[ (b['t']-a['t']) for a,b in zip(recs,recs[1:])]
    oidsw=[]
    prev=None
    for r in ok:
        k=tuple(r['oids'])
        if k!=prev: oidsw.append((round(r['t']-t0,2),k)); prev=k
    during=[r for r in ok if t0<=r['t']<=t1]
    dupn=[r['n'] for r in ok if r['n']>5]
    res={'mode':tag,'wall_s':round(t1-t0,1),'base_mb':base,'peak_mb':peak[0],'peak_extra_mb':peak[0]-base,'reads':len(recs),'errors':len(recs)-len(ok),'lat_median_ms':statistics.median(ms),'lat_max_ms':max(ms),'longest_gap_between_starts_s':max(gaps),'reads_during':len(during),'oid_switches':oidsw,'reads_with_dup_rows':len(dupn),'steps':steps}
    if extra: res.update(extra)
    json.dump(res,open(f'refresh_{tag}.json','w'),indent=1,default=str); print(json.dumps(res,default=str))
threading.Thread(target=samp,daemon=True).start()
rp=start_reader(mode) if mode!='S' else None; time.sleep(3 if mode!='S' else 0)
if mode=='A':
    t0=time.time()
    step('create','CREATE TABLE part_index_new (LIKE part_index INCLUDING DEFAULTS INCLUDING CONSTRAINTS)')
    copy_in('part_index_new'); build('part_index_new','_n'); step('analyze','ANALYZE part_index_new')
    ren=["ALTER TABLE part_index RENAME TO part_index_old","ALTER TABLE part_index_new RENAME TO part_index"]
    ren=[f"ALTER INDEX {n} RENAME TO {n}_old" for n,_,_ in IDX]+["ALTER INDEX part_index_pkey RENAME TO part_index_old_pkey"]+ren[:1]+ren[1:]
    ren+= ["ALTER INDEX part_index_new_pkey RENAME TO part_index_pkey"]+[f"ALTER INDEX {n}_n RENAME TO {n}" for n,_,_ in IDX]
    ts=time.time()
    step('swap txn',"BEGIN; "+"; ".join(ren)+"; COMMIT")
    sw=time.time()
    step('drop old','DROP TABLE part_index_old')
    t1=time.time(); finish('A',rp,t0,t1,{'swap_t_rel':round(ts-t0,1)})
elif mode=='C':
    t0=time.time()
    c.autocommit=False
    t=time.perf_counter()
    c.execute('TRUNCATE part_index'); steps.append(('truncate',round(time.perf_counter()-t,2)))
    t=time.perf_counter(); c.execute(f"COPY part_index ({COLS}) FROM PROGRAM 'cat /data/chunk_*.csv' WITH (FORMAT csv)"); steps.append(('copy w/ 13 idx',round(time.perf_counter()-t,2)))
    t=time.perf_counter(); c.commit(); steps.append(('commit',round(time.perf_counter()-t,2)))
    c.autocommit=True
    step('analyze','ANALYZE part_index')
    t1=time.time(); finish('C',rp,t0,t1)
elif mode in ('B','B2','S'):
    gen=int(sys.argv[2]); prev=gen-1
    t0=time.time()
    step('create','CREATE TABLE part_g%d (LIKE part_index INCLUDING DEFAULTS)'%gen)
    step('gen default',f"ALTER TABLE part_g{gen} ALTER generation SET DEFAULT {gen}")
    step('check',f"ALTER TABLE part_g{gen} ADD CONSTRAINT g{gen}_chk CHECK (generation = {gen})")
    copy_in(f'part_g{gen}')
    step('pk',f"ALTER TABLE part_g{gen} ADD PRIMARY KEY (generation, distributor, part_number)")
    for s in ddl(f'part_g{gen}',f'_g{gen}'): step(s.split()[2],s)
    step('analyze',f'ANALYZE part_g{gen}')
    ts=time.time()
    if mode=='S':
        step('attach',f"ALTER TABLE part_index ATTACH PARTITION part_g{gen} FOR VALUES IN ({gen})"); sys.exit(0)
    if mode=='B':
        step('attach',f"ALTER TABLE part_index ATTACH PARTITION part_g{gen} FOR VALUES IN ({gen})")
        step('detach concurrently',f"ALTER TABLE part_index DETACH PARTITION part_g{prev} CONCURRENTLY")
    else:
        step('swap txn (detach+attach)',f"BEGIN; ALTER TABLE part_index DETACH PARTITION part_g{prev}; ALTER TABLE part_index ATTACH PARTITION part_g{gen} FOR VALUES IN ({gen}); COMMIT")
    step('drop old',f'DROP TABLE part_g{prev}')
    t1=time.time(); finish(mode,rp,t0,t1,{'swap_t_rel':round(ts-t0,1)})
