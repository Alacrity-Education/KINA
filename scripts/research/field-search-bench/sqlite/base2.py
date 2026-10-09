import sys, json, os; sys.path.insert(0,'.')
from kb import *
def evict():
    fd=os.open('/var/tmp/kina-bench/parts-fts5.db',os.O_RDONLY); os.posix_fadvise(fd,0,0,os.POSIX_FADV_DONTNEED); os.close(fd)
out={}
def run(name, terms):
    c = connect()
    p = conjunction(terms)
    evict()
    t=time.perf_counter(); n=count(c,p); cf=time.perf_counter()-t
    t=time.perf_counter(); rows=page(c,p); pf=time.perf_counter()-t
    cw=[];pw=[]
    for _ in range(5):
        t=time.perf_counter(); count(c,p); cw.append(time.perf_counter()-t)
        t=time.perf_counter(); page(c,p); pw.append(time.perf_counter()-t)
    r=dict(total=n,rows=len(rows),count_cold=cf,page_cold=pf,count_warm_med=statistics.median(cw),page_warm_med=statistics.median(pw),
           search_cold=cf+pf, search_warm_med=statistics.median(cw)+statistics.median(pw))
    out[name]=r; print(name,{k:(round(v,4) if isinstance(v,float) else v) for k,v in r.items()},flush=True)
for name,terms in Q.items(): run(name,terms)
# Q6: ANY (OR fallback)
c=connect(); p=any_pred(Q6); evict()
t=time.perf_counter(); n=count(c,p); cf=time.perf_counter()-t
t=time.perf_counter(); rows=page(c,p); pf=time.perf_counter()-t
cw=[];pw=[]
for _ in range(5):
    t=time.perf_counter(); count(c,p); cw.append(time.perf_counter()-t)
    t=time.perf_counter(); page(c,p); pw.append(time.perf_counter()-t)
out["Q6 OR fallback (6 terms)"]=dict(total=n,rows=len(rows),count_cold=cf,page_cold=pf,count_warm_med=statistics.median(cw),page_warm_med=statistics.median(pw),search_cold=cf+pf,search_warm_med=statistics.median(cw)+statistics.median(pw),match=p[1][0])
print(out["Q6 OR fallback (6 terms)"],flush=True)
# relaxation flow
for nm,terms in (("QR relax 10uF X7R 0805 1% 100V",QR),("Q6 as ALL then relax",Q6)):
    c=connect(); evict(); tr=[]
    t=time.perf_counter(); mode,n,rows,dr=search(c,terms,tr); cold=time.perf_counter()-t
    ws=[]
    for _ in range(3):
        t=time.perf_counter(); search(c,terms); ws.append(time.perf_counter()-t)
    out[nm]=dict(mode=mode,total=n,rows=len(rows),dropped=dr,trace=tr,cold=cold,warm_med=statistics.median(ws))
    print(nm,out[nm],flush=True)
json.dump(out,open("../res_base2.json","w"),indent=1,default=str)
