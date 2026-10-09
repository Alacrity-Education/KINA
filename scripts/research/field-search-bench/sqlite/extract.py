import sys, re, time, sqlite3, json, os; sys.path.insert(0,'.')
OUT="/var/tmp/kina-bench/sqlite/part_index.db"
if os.path.exists(OUT): os.remove(OUT)
src=sqlite3.connect("file:/var/tmp/kina-bench/parts-fts5.db?mode=ro",uri=True)
dst=sqlite3.connect(OUT)
dst.execute("""CREATE TABLE part_index(rowid INTEGER PRIMARY KEY, lcsc TEXT, family TEXT, value_num REAL, voltage_v REAL,
 tolerance_pct REAL, power_w REAL, package TEXT, dielectric TEXT, mounting TEXT, technology TEXT, stock_int INTEGER)""")
NUM=r"(\d+(?:\.\d+)?)"
PFX={'p':1e-12,'n':1e-9,'u':1e-6,'m':1e-3,'':1.0,'k':1e3,'K':1e3,'M':1e6,'G':1e9}
R_F=re.compile(NUM+r"\s?([pnumµ]?)F\b")
R_OHM=re.compile(NUM+r"\s?([mkKMG]?)Ω")
R_H=re.compile(NUM+r"\s?([nuµm]?)H\b")
R_V=re.compile(r"(?<![\d.])"+NUM+r"\s?([mk]?)V\b")
R_W=re.compile(r"(?<![\d.])"+NUM+r"\s?([mk]?)W\b")
R_TOL=re.compile(r"±\s?"+NUM+r"%")
R_DI=re.compile(r"\b(X7R|X5R|C0G|NP0|NPO|Y5V|X7S|X6S|X8R|X7T|Z5U)\b",re.I)
R_THT=re.compile(r"Plugin|插件|弯插|Through Hole|THT|DIP",re.I)
R_SMD=re.compile(r"\bSMD|SMT|Surface Mount|贴片",re.I)
R_CHIP=re.compile(r"^(01005|0201|0402|0603|0805|1008|1206|1210|1806|1812|2010|2220|2512|SOT|SOD|SOIC|SOP|TSSOP|QFN|DFN|QFP|BGA)")
R_TECH=re.compile(r"(Thick Film|Thin Film|Metal Film|Carbon Film|Wirewound|Metal Oxide|Metal Glaze|Ceramic|Tantalum|Electrolytic|Polymer|Polypropylene|Polyester|Mica|Supercap)",re.I)
FAM=[("capacitor",re.compile("Capacitor",re.I)),("resistor",re.compile("Resistor|Potentiometer",re.I)),
 ("inductor",re.compile("Inductor|Choke",re.I)),("ferrite",re.compile("Ferrite|Bead",re.I)),
 ("diode",re.compile("Diode|Rectifier|TVS|Zener",re.I)),("led",re.compile(r"\bLED",re.I)),
 ("transistor",re.compile("MOSFET|Transistor|Thyristor|IGBT",re.I)),("connector",re.compile("Connector|Header|Socket|Terminal",re.I)),
 ("ic",re.compile("Microcontroller|Amplifier|Regulator|Converter|Memory|Interface|Driver|Logic|Timer|Comparator|Sensor",re.I))]
def val(rx,s,mult=PFX):
    m=rx.search(s)
    if not m: return None
    return float(m.group(1))*mult[m.group(2).replace('µ','u')]
def row(rid,lcsc,cat1,cat2,pkg,desc,stock):
    cat=(cat2 or "")+" "+(cat1 or ""); fam=None
    for f,rx in FAM:
        if rx.search(cat): fam=f;break
    d=desc or ""
    v=None
    if fam=="capacitor": v=val(R_F,d)
    elif fam=="resistor": v=val(R_OHM,d)
    elif fam in("inductor","ferrite"): v=val(R_H,d) if fam=="inductor" else val(R_OHM,d)
    volt=val(R_V,d) if fam in("capacitor","diode","transistor","resistor","led","inductor","ic",None) else None
    tol=None; m=R_TOL.search(d)
    if m: tol=float(m.group(1))
    pw=val(R_W,d) if fam in("resistor","diode","inductor",None) else None
    p=(pkg or "").split(",")[0].strip() or None
    if p=="-": p=None
    di=None
    if fam=="capacitor":
        m=R_DI.search(d)
        if m: di=m.group(1).upper()
    pk=(pkg or "")+" "+d
    mnt="THT" if R_THT.search(pk) else ("SMD" if (R_SMD.search(pk) or R_CHIP.match(p or "")) else None)
    m=R_TECH.search(cat+" "+d); tech=m.group(1).lower() if m else None
    try: st=int(stock)
    except: st=0
    return (rid,lcsc,fam,v,volt,tol,pw,p,di,mnt,tech,st)
t0=time.time(); n=0; batch=[]
cur=src.execute('select rowid,"LCSC Part","First Category","Second Category",Package,Description,Stock from parts')
tw=0.0
for r in cur:
    batch.append(row(r[0],r[1],r[2],r[3],r[4],r[5],r[6])); n+=1
    if len(batch)==100000:
        t=time.time()
        dst.execute("BEGIN"); dst.executemany("INSERT INTO part_index VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",batch); dst.execute("COMMIT"); tw+=time.time()-t
        batch=[]
        if n%1000000==0: print(n,round(time.time()-t0,1),flush=True)
if batch:
    t=time.time(); dst.execute("BEGIN"); dst.executemany("INSERT INTO part_index VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",batch); dst.execute("COMMIT"); tw+=time.time()-t
tot=time.time()-t0
print(json.dumps(dict(rows=n,total_s=tot,insert_s=tw,scan_extract_s=tot-tw,size=os.path.getsize(OUT))))
json.dump(dict(rows=n,total_s=tot,insert_s=tw,scan_extract_s=tot-tw,size=os.path.getsize(OUT)),open("../res_extract.json","w"))
