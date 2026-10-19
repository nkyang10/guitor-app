import numpy as np, dsp, synth, json
rng=np.random.default_rng(7); SR=48000
chords=list(synth.CHORDS.items())
def seq(kind,bpm=100,n=32):
    step=60/bpm/2; sig=np.zeros(int((n*step+2.5)*SR)); times=[]; t=0.5
    prog=[chords[rng.integers(len(chords))] for _ in range(4)]
    for i in range(n):
        if rng.random()<0.2 and i>0: t+=step; continue
        name,frets=prog[(i//8)%4]          # realistic: chord held for a bar
        if kind=='strum':
            y,h=synth.strum(frets,"D" if i%2==0 else "U",dur=1.5,spread_ms=rng.uniform(8,40),rng=rng,amp=rng.uniform(0.4,1))
        else:
            s=[i for i in range(6) if frets[i]>=0][rng.integers(0,len([f for f in frets if f>=0]))]
            y=synth.pluck(440*2**((synth.OPEN[s]+max(0,frets[s])-69)/12),1.5,rng=rng,amp=rng.uniform(0.3,1))
        a=int((t+rng.normal(0,0.008))*SR); sig[a:a+len(y)]+=y[:len(sig)-a]; times.append(a/SR); t+=step
    return sig,np.array(times)
data=[]
for kind in ['strum','pluck']:
    for bpm in [70,100,130,160]:
        for rep in range(2):
            s,tt=seq(kind,bpm); data.append((kind,bpm,synth.phone_mic(s,rng=rng,snr_db=rng.uniform(25,40),hp_hz=130),tt))
def match(est,ref,tol=0.05):
    used=set(); d=[]
    for r in ref:
        c=[(abs(e-r),i) for i,e in enumerate(est) if i not in used and abs(e-r)<=tol]
        if c: _,i=min(c); used.add(i); d.append(est[i]-r)
    P=len(d)/max(1,len(est)); R=len(d)/max(1,len(ref)); return P,R,2*P*R/max(1e-9,P+R),np.array(d)
rows=[]
cfgs=[('flux',0.3,1.5),('flux',0.6,1.2)]+[('norm',d,m) for d in (0.05,0.1,0.15,0.2) for m in (1.0,1.3,1.6)]
for algo,delta,mult in cfgs:
    det=dsp.SpectralFlux(SR,delta=delta,mult=mult) if algo=='flux' else dsp.NormFlux(SR,delta=delta,mult=mult)
    F={'strum':[],'pluck':[]}; D=[]; PR=[]; Fb={}
    for kind,bpm,x,tt in data:
        on,_=det.detect(x)
        P,R,f,d=match(on,tt); F[kind].append(f); D+=list(d*1000); PR.append((P,R)); Fb.setdefault(bpm,[]).append(f)
    D=np.array(D)
    row=dict(algo=algo,delta=delta,mult=mult,F_strum=float(np.mean(F['strum'])),F_pluck=float(np.mean(F['pluck'])),P=float(np.mean([p for p,_ in PR])),R=float(np.mean([r for _,r in PR])),bias=float(D.mean()),sd=float(D.std()),p95dev=float(np.percentile(abs(D-D.mean()),95)),F_by_bpm={k:round(float(np.mean(v)),3) for k,v in Fb.items()})
    rows.append(row); print({k:(round(v,3) if isinstance(v,float) else v) for k,v in row.items()}, flush=True)
json.dump(rows,open('results_onset_synth.json','w'),indent=1)
