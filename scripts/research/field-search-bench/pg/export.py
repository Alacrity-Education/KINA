import sqlite3, re, csv, json, sys, time, os
from multiprocessing import Pool
DB='file:/var/tmp/kina-bench/parts-fts5.db?mode=ro'
COLS=['distributor','part_number','family','lcsc','mpn','manufacturer','description','category1','category2','package','value_num','voltage_v','tolerance_pct','power_w','dielectric','mounting','technology','stock_int','price_text','attributes']
MULT={'p':1e-12,'n':1e-9,'u':1e-6,'µ':1e-6,'μ':1e-6,'m':1e-3,'k':1e3,'K':1e3,'M':1e6,'G':1e9,'':1.0}
RC=re.compile(r'(\d+(?:\.\d+)?)\s*([pnuµμ])F')
RR=re.compile(r'(\d+(?:\.\d+)?)\s*([kKMmG]?)[ΩΩ]')
RR2=re.compile(r'\b(\d+)([kKM])(\d+)\b')
RL=re.compile(r'(\d+(?:\.\d+)?)\s*([nuµμm])H')
RV=re.compile(r'(\d+(?:\.\d+)?)\s*V\b')
RT=re.compile(r'±?\s*(\d+(?:\.\d+)?)\s*%')
RP=re.compile(r'(\d+(?:\.\d+)?)\s*(m)?W\b')
RP2=re.compile(r'\b1/(\d+)\s*W\b')
RD=re.compile(r'X7R|X5R|C0G|NP0|Y5V|X7S|X6S')
TECH=[('thin film','thin film'),('thick film','thick film'),('metal film','metal film'),('carbon film','carbon film'),('wirewound','wirewound'),('tantalum','tantalum'),('electrolytic','electrolytic'),('ceramic','ceramic'),('film','film')]
def fam(c1,c2):
    s=(c1+' '+c2).lower()
    for k,v in (('resistor','resistor'),('capacitor','capacitor'),('inductor','inductor'),('diode','diode'),('connector','connector')):
        if k in s: return v
    return 'other'
def work(a):
    lo,hi,i=a
    c=sqlite3.connect(DB,uri=True)
    out=open(f'chunk_{i:02d}.csv','w',newline='',encoding='utf-8')
    w=csv.writer(out)
    nulls=[0]*len(COLS); n=0
    for r in c.execute('select "LCSC Part","First Category","Second Category","MFR.Part","Package","Manufacturer","Description","Price","Stock" from parts where rowid>=? and rowid<?',(lo,hi)):
        lcsc,c1,c2,mpn,pkg,mfr,desc,price,stock=r
        c1=c1 or '';c2=c2 or '';desc=(desc or '').replace('\x00','')
        f=fam(c1,c2)
        val=None;attrs={}
        if f=='capacitor':
            m=RC.search(desc)
            if m: val=float(m.group(1))*MULT[m.group(2)]; attrs['Capacitance']=m.group(0).replace(' ','')
        elif f=='resistor':
            m=RR.search(desc)
            if m: val=float(m.group(1))*MULT[m.group(2)]; attrs['Resistance']=m.group(0).replace(' ','')
            else:
                m=RR2.search(desc)
                if m: val=float(m.group(1)+'.'+m.group(3))*MULT[m.group(2)]; attrs['Resistance']=m.group(0)
        elif f=='inductor':
            m=RL.search(desc)
            if m: val=float(m.group(1))*MULT[m.group(2)]; attrs['Inductance']=m.group(0).replace(' ','')
        v=None;m=RV.search(desc)
        if m: v=float(m.group(1)); attrs['Voltage']=m.group(0).replace(' ','')
        t=None;m=RT.search(desc)
        if m: t=float(m.group(1)); attrs['Tolerance']='±'+m.group(1)+'%'
        p=None
        if f=='resistor':
            m=RP2.search(desc)
            if m: p=1/float(m.group(1)); attrs['Power']=m.group(0)
            else:
                m=RP.search(desc)
                if m: p=float(m.group(1))*(1e-3 if m.group(2) else 1); attrs['Power']=m.group(0).replace(' ','')
        d=None
        if f=='capacitor':
            m=RD.search(desc)
            if m: d=m.group(0); attrs['Dielectric']=d
        dl=(desc+' '+c2).lower()
        mount=None
        if 'surface mount' in dl or 'smd' in dl: mount='SMD'
        elif 'through hole' in dl or '插件' in dl or 'through-hole' in dl: mount='THT'
        tech=None
        tl=(desc+' '+c1+' '+c2).lower()
        for k,vv in TECH:
            if k in tl: tech=vv;break
        if tech: attrs['Technology']=tech
        if pkg: attrs['Package']=pkg
        try: st=int(stock) if stock and stock.isdigit() else 0
        except: st=0
        row=['LCSC',lcsc,f,lcsc,mpn or None,mfr or None,desc or None,c1 or None,c2 or None,pkg or None,
             repr(val) if val is not None else None,v,t,repr(p) if p is not None else None,d,mount,tech,st,price or None,json.dumps(attrs,ensure_ascii=False)]
        for j,x in enumerate(row):
            if x is None or x=='': nulls[j]+=1
        w.writerow(row); n+=1
    out.close()
    return n,nulls
if __name__=='__main__':
    c=sqlite3.connect(DB,uri=True)
    mx=c.execute('select max(rowid) from parts').fetchone()[0]
    N=48; step=mx//N+1
    t=time.time()
    with Pool(12) as p:
        res=p.map(work,[(i*step,(i+1)*step,i) for i in range(N)])
    tot=sum(r[0] for r in res); nulls=[sum(r[1][j] for r in res) for j in range(len(COLS))]
    el=time.time()-t
    json.dump({'rows':tot,'seconds':el,'null_rate':{COLS[j]:nulls[j]/tot for j in range(len(COLS))}},open('export_stats.json','w'),indent=1)
    print(tot,el)
