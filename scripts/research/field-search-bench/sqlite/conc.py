import sys, json, threading, multiprocessing as mp; sys.path.insert(0,'.')
from kb import *
W = [Q["Q1 10uF X7R 0805"],Q["Q2 4.7k 0603"],Q["Q3 Female Header 1x6P Right Angle"],Q["Q4 Thin Film 5.36k 0805"],
 [T("100nF","VALUE"),T("X7R","DIELECTRIC"),T("0402","PACKAGE")],
 [T("10kΩ","VALUE"),T("0805","PACKAGE")],
 [T("Pin Header","CATEGORY",["Pin Header"],"Second Category"),T("2x10P","POSITIONS"),T("Right Angle","ORIENTATION",["Right Angle"])],
 [T("Schottky","FAMILY"),T("SOD-123","PACKAGE")]]
ROUNDS=20
def one(c, terms):
    p=conjunction(terms); count(c,p); page(c,p)
def proc_worker(i, barrier, q):
    c=connect(); one(c,W[i]); barrier.wait()
    t=time.perf_counter()
    for _ in range(ROUNDS): one(c,W[i])
    q.put(time.perf_counter()-t)
def run_mode(mode):
    if mode=="sequential_1conn":
        c=connect(); [one(c,w) for w in W]
        t=time.perf_counter()
        for w in W:
            for _ in range(ROUNDS): one(c,w)
        return time.perf_counter()-t
    if mode=="processes_own_conn":
        b=mp.Barrier(9); qq=mp.Queue(); ps=[mp.Process(target=proc_worker,args=(i,b,qq)) for i in range(8)]
        [p.start() for p in ps]; b.wait(); t=time.perf_counter(); [p.join() for p in ps]; return time.perf_counter()-t
    conns=[connect() for _ in range(8)] if mode=="threads_own_conn" else None
    shared=connect() if mode!="threads_own_conn" else None
    lock=threading.Lock()
    for i in range(8): one(conns[i] if conns else shared, W[i])
    b=threading.Barrier(9)
    def work(i):
        b.wait()
        for _ in range(ROUNDS):
            if mode=="threads_own_conn": one(conns[i],W[i])
            elif mode=="threads_shared_conn_lock":
                with lock: one(shared,W[i])
            else: one(shared,W[i])   # shared, no python lock: SQLite serialized mutex
    ths=[threading.Thread(target=work,args=(i,)) for i in range(8)]
    [t.start() for t in ths]; b.wait(); t=time.perf_counter(); [t.join() for t in ths]; return time.perf_counter()-t
if __name__=="__main__":
    res={}
    for mode in ["sequential_1conn","threads_shared_conn_lock","threads_own_conn","processes_own_conn"]:
        ws=[run_mode(mode) for _ in range(3)]
        n=8*ROUNDS; med=statistics.median(ws)
        res[mode]=dict(wall_s=ws,searches=n,median_wall=med,throughput_per_s=n/med)
        print(mode,res[mode],flush=True)
    json.dump(res,open("../res_conc.json","w"),indent=1)
