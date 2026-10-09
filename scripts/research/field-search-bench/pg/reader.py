import psycopg, time, json, os, sys
out=open(sys.argv[1],'w')
c=psycopg.connect(os.environ["PGBENCH_DSN"],autocommit=True)
q="SELECT part_number,stock_int,tableoid::oid FROM part_index WHERE family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40"
while not os.path.exists('stop'):
    t=time.time(); p=time.perf_counter()
    try:
        r=c.execute(q,prepare=True).fetchall(); err=None
        rec={'t':t,'ms':(time.perf_counter()-p)*1000,'n':len(r),'oids':sorted({x[2] for x in r})}
    except Exception as e:
        rec={'t':t,'ms':(time.perf_counter()-p)*1000,'err':str(e)[:200]}
        try: c.rollback()
        except Exception: pass
    out.write(json.dumps(rec)+'\n'); out.flush()
    time.sleep(max(0,0.2-(time.perf_counter()-p)))
