import numpy as np, dsp, synth, json, sys
rng=np.random.default_rng(7); SR=48000
chords=list(synth.CHORDS.items())
def seq(kind,bpm=100,n=24):
    step=60/bpm/2; sig=np.zeros(int((n*step+2.5)*SR)); times=[]; t=0.5
    for i in range(n):
        if rng.random()<0.2 and i>0: t+=step; continue
        name,frets=chords[rng.integers(len(chords))]
        if kind=='strum':
            y,h=synth.strum(frets,"D" if i%2==0 else "U",dur=1.5,spread_ms=rng.uniform(8,40),rng=rng,amp=rng.uniform(0.4,1))
        else:
            s=rng.integers(0,6); y=synth.pluck(440*2**((synth.OPEN[s]+max(0,frets[s])-69)/12),1.5,rng=rng,amp=rng.uniform(0.3,1))
        a=int((t+rng.normal(0,0.008))*SR); sig[a:a+len(y)]+=y[:len(sig)-a]; times.append(a/SR); t+=step
    return sig,np.array(times)
data=[]
for kind in ['strum','pluck']:
    for bpm in [70,100,130,160]:
        for rep in range(2):
            s,tt=seq(kind,bpm); data.append((kind,synth.phone_mic(s,rng=rng,snr_db=rng.uniform(25,40),hp_hz=130),tt))
def match(est,ref,tol=0.05):
    used=set(); d=[]
    for r in ref:
        c=[(abs(e-r),i) for i,e in enumerate(est) if i not in used and abs(e-r)<=tol]
        if c: _,i=min(c); used.add(i); d.append(est[i]-r)
    P=len(d)/max(1,len(est)); R=len(d)/max(1,len(ref)); return P,R,2*P*R/max(1e-9,P+R),np.array(d)
rows=[]
for algo in ['band','flux']:
    grid=[(1.5,1.0),(2.5,1.0),(3.5,1.0),(2.0,1.5),(3.0,1.5)] if algo=='band' else [(0.3,1.2),(0.6,1.2),(0.3,1.5),(1.0,1.2)]
    for delta,mult in grid:
        det=dsp.BandFlux(SR,delta=delta,mult=mult) if algo=='band' else dsp.SpectralFlux(SR,delta=delta,mult=mult)
        F={'strum':[],'pluck':[]}; D=[]; PR=[]
        for kind,x,tt in data:
            on,_=det.detect(x); on=np.array([dsp.refine_onset(x,SR,t) for t in on])
            P,R,f,d=match(on,tt); F[kind].append(f); D+=list(d*1000); PR.append((P,R))
        D=np.array(D)
        row=dict(algo=algo,delta=delta,mult=mult,F_strum=float(np.mean(F['strum'])),F_pluck=float(np.mean(F['pluck'])),P=float(np.mean([p for p,_ in PR])),R=float(np.mean([r for _,r in PR])),bias=float(D.mean()),sd=float(D.std()),p95dev=float(np.percentile(abs(D-D.mean()),95)))
        rows.append(row); print({k:(round(v,3) if isinstance(v,float) else v) for k,v in row.items()}, flush=True)
json.dump(rows,open('results_onset_synth.json','w'),indent=1)
