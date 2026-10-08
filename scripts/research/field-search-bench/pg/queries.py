import subprocess, re, json, statistics, sys
Q1W="family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0"
Q={
'Q1 all-match (abs form)': f"SELECT part_number,stock_int FROM part_index WHERE {Q1W} ORDER BY stock_int DESC LIMIT 40",
'Q1s Q1 sargable value range': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND value_num BETWEEN 9.9e-6 AND 10.1e-6 AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q2 no dielectric': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND package='0805' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q3 no package': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q4 resistor 4.7k 1% 0603': "SELECT part_number,stock_int FROM part_index WHERE family='resistor' AND abs(value_num-4700)<=4700*0.01 AND package='0603' AND (tolerance_pct IS NULL OR tolerance_pct<=1) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q5 fts thin film + 5.36k': "SELECT part_number,stock_int FROM part_index WHERE to_tsvector('simple',description) @@ plainto_tsquery('simple','thin film') AND family='resistor' AND abs(value_num-5360)<=5360*0.01 AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q5b fts only count': "SELECT count(*) FROM part_index WHERE to_tsvector('simple',description) @@ plainto_tsquery('simple','thin film')",
'Q6a trigram ILIKE %X7R% count': "SELECT count(*) FROM part_index WHERE description ILIKE '%X7R%'",
'Q6a2 trigram ILIKE %X7R% limit 40': "SELECT part_number FROM part_index WHERE description ILIKE '%X7R%' AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q6b trigram % X7R count': "SELECT count(*) FROM part_index WHERE description % 'X7R'",
'Q6c mpn ILIKE %ERA6AEB% count': "SELECT count(*) FROM part_index WHERE mpn ILIKE '%ERA6AEB%'",
'Q6c2 mpn ILIKE %ERA6AEB% limit 40': "SELECT part_number FROM part_index WHERE mpn ILIKE '%ERA6AEB%' AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q7 jsonb @> dielectric + value': "SELECT part_number,stock_int FROM part_index WHERE attributes @> '{\"Dielectric\":\"X7R\"}' AND abs(value_num-10e-6)<=10e-6*0.01 AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q7b jsonb @> dielectric+capacitance string': "SELECT part_number,stock_int FROM part_index WHERE attributes @> '{\"Dielectric\":\"X7R\",\"Capacitance\":\"10uF\"}' AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
'Q8 count(*) of Q1 no LIMIT': f"SELECT count(*) FROM part_index WHERE {Q1W}",
'Q9 Q1 + three IS NULL OR ratings': "SELECT part_number,stock_int FROM part_index WHERE family='capacitor' AND abs(value_num-10e-6)<=10e-6*0.01 AND package='0805' AND dielectric='X7R' AND (voltage_v IS NULL OR voltage_v>=25) AND (tolerance_pct IS NULL OR tolerance_pct<=10) AND (power_w IS NULL OR power_w>=0.1) AND stock_int>0 ORDER BY stock_int DESC LIMIT 40",
}
def run(sql):
    p=subprocess.run(['docker','exec','-i','kina-pgbench','psql','-U','postgres','-d','bench','-At'],input="EXPLAIN (ANALYZE, BUFFERS) "+sql,capture_output=True,text=True)
    return p.stdout+p.stderr
def parse(t):
    ex=float(re.search(r'Execution Time: ([\d.]+)',t).group(1)); pl=float(re.search(r'Planning Time: ([\d.]+)',t).group(1))
    first=t.splitlines()[0]
    rows=re.search(r'actual time=[\d.]+\.\.[\d.]+ rows=(\d+)',first).group(1)
    idx=sorted(set(re.findall(r'(?:Index(?: Only)? Scan(?: Backward)?|Bitmap Index Scan) (?:using|on) (\w+)',t))|set(re.findall(r'Bitmap Index Scan on (\w+)',t)))
    seq='Seq Scan' in t
    b=re.search(r'Buffers: shared (.*)',t)
    bs=b.group(1).split(' dirtied')[0] if b else ''
    return ex,pl,rows,idx,seq,bs
res={}
for k,sql in Q.items():
    runs=[run(sql) for _ in range(6)]
    P=[parse(r) for r in runs]
    warm=[p[0] for p in P[1:]]
    res[k]={'first_ms':P[0][0],'warm_median_ms':statistics.median(warm),'warm_min':min(warm),'warm_max':max(warm),'plan_ms':P[1][1],'rows':P[1][2],'idx':P[1][3],'seq':P[1][4],'buffers_first':P[0][5],'buffers_warm':P[1][5]}
    open('plan_'+k.split()[0]+'.txt','w').write(runs[1])
    print(k,json.dumps(res[k]),flush=True)
json.dump(res,open('queries.json','w'),indent=1)
