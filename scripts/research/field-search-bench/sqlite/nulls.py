import sqlite3, json
c=sqlite3.connect("/var/tmp/kina-bench/sqlite/part_index.db")
cols=["lcsc","family","value_num","voltage_v","tolerance_pct","power_w","package","dielectric","mounting","technology","stock_int"]
n=c.execute("select count(*) from part_index").fetchone()[0]
res={"rows":n,"in_stock":c.execute("select count(*) from part_index where stock_int>0").fetchone()[0],"cols":{}}
for col in cols:
    nn,d=c.execute(f"select count({col}),count(distinct {col}) from part_index").fetchone()
    res["cols"][col]=dict(non_null=nn,null_pct=round(100*(n-nn)/n,2),distinct=d)
res["fam"]=[]
for r in c.execute("""select coalesce(family,'(null)'),count(*),sum(stock_int>0),
 round(100.0*sum(value_num is null)/count(*),1),round(100.0*sum(voltage_v is null)/count(*),1),round(100.0*sum(tolerance_pct is null)/count(*),1),
 round(100.0*sum(package is null)/count(*),1),round(100.0*sum(dielectric is null)/count(*),1),round(100.0*sum(mounting is null)/count(*),1) from part_index group by 1 order by 2 desc"""): res["fam"].append(r)
res["fam_instock"]=[]
for r in c.execute("""select coalesce(family,'(null)'),count(*),
 round(100.0*sum(value_num is null)/count(*),1),round(100.0*sum(voltage_v is null)/count(*),1),round(100.0*sum(tolerance_pct is null)/count(*),1),
 round(100.0*sum(package is null)/count(*),1),round(100.0*sum(dielectric is null)/count(*),1),round(100.0*sum(mounting is null)/count(*),1) from part_index where stock_int>0 group by 1 order by 2 desc"""): res["fam_instock"].append(r)
json.dump(res,open("../res_nulls.json","w"),indent=1)
print(json.dumps(res,indent=1))
