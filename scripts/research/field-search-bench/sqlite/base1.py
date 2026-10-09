import sys, json; sys.path.insert(0,'.')
from kb import *
c = connect()
print(c.execute("select count(*) from parts_content").fetchone())
out = {}
for name, terms in Q.items():
    p = conjunction(terms)
    f,m,lo,hi,n = timeit(lambda: count(c,p)); 
    f2,m2,lo2,hi2,rows = timeit(lambda: page(c,p))
    out[name] = dict(count=n, count_first=f, count_med=m, page_first=f2, page_med=m2, page_min=lo2, page_max=hi2, rows=len(rows), where=p[0], params=p[1])
    print(name, out[name], flush=True)
json.dump(out, open("../res_base1.json","w"), indent=1)
