import psycopg, time, statistics, json, sys, random, os
label=sys.argv[1]
c=psycopg.connect(os.environ["PGBENCH_DSN"],autocommit=True)
cols="distributor,part_number,family,lcsc,mpn,manufacturer,description,category1,category2,package,value_num,voltage_v,tolerance_pct,power_w,dielectric,mounting,technology,stock_int,price_text,attributes"
colist=cols.split(',')
src=c.execute(f"SELECT {cols} FROM part_index TABLESAMPLE SYSTEM (3) WHERE distributor='LCSC' AND stock_int>0 LIMIT 10000").fetchall()
random.seed(1); random.shuffle(src)
assert len(src)==10000,len(src)
from psycopg.types.json import Jsonb
def batch(i,upd):
    rows=[]
    for j,r in enumerate(src[i*50:(i+1)*50]):
        r=list(r); r[0]='MOUSER'; r[1]=f"BENCH-{label}-{i*50+j}"; r[3]=r[1]
        if upd: r[17]=r[17]+7; r[18]='1-:0.00'+str(i%9+1)
        r[19]=Jsonb(r[19]); rows.append(r)
    return rows
ph=",".join(["%s"]*20); 
sets=",".join(f"{k}=EXCLUDED.{k}" for k in colist[2:])
def sql(n): return f"INSERT INTO part_index ({cols}) VALUES "+",".join([f"({ph})"]*n)+f" ON CONFLICT (distributor,part_number) DO UPDATE SET {sets}"
def lsn(): return c.execute("select pg_current_wal_lsn()").fetchone()[0]
def stats():
    return c.execute("select n_dead_tup,n_tup_ins,n_tup_upd,n_tup_hot_upd,autovacuum_count,autoanalyze_count from pg_stat_user_tables where relname='part_index'").fetchone()
out={}
for phase,upd in (('insert',False),('update',True)):
    lat=[];wal=[];cm=[]
    for i in range(200):
        rows=batch(i,upd); flat=[x for r in rows for x in r]
        l0=lsn()
        t=time.perf_counter()
        with c.transaction():
            c.execute(sql(len(rows)),flat)
            t1=time.perf_counter()
        t2=time.perf_counter()
        lat.append((t2-t)*1000); cm.append((t2-t1)*1000)
        l1=lsn()
        wal.append(c.execute("select pg_wal_lsn_diff(%s,%s)",(l1,l0)).fetchone()[0])
    lat_s=sorted(lat)
    out[phase]={'median_ms':statistics.median(lat),'p95_ms':lat_s[int(0.95*len(lat))-1],'p99_ms':lat_s[int(0.99*len(lat))-1],'max_ms':max(lat),'mean_ms':statistics.mean(lat),'commit_median_ms':statistics.median(cm),'wal_bytes_per_batch_median':statistics.median(wal),'wal_bytes_per_batch_mean':statistics.mean(wal),'wal_total':sum(wal)}
    print(phase,out[phase],flush=True)
time.sleep(1)
out['stats']=stats()
out['pgstattuple_approx']=c.execute("select table_len,approx_tuple_count,dead_tuple_count,dead_tuple_percent,approx_free_space,approx_free_percent from pgstattuple_approx('part_index')").fetchone()
out['relsize']=c.execute("select pg_relation_size('part_index'),pg_total_relation_size('part_index')").fetchone()
print(out['stats'],out['pgstattuple_approx'],out['relsize'])
json.dump(out,open(f'write_{label}.json','w'),default=str,indent=1)
