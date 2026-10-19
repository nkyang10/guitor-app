"""Expectation-guided acceptance: weak peaks (ratio>=g) are accepted only inside an expected window."""
import numpy as np, dsp, synth, json
exec(open('quick_onset2.py').read().split("rows=[]")[0])   # reuse data generation + match()
def candidates(det, x, norm):
    raw = det.odf(x)
    if norm:
        env=1e-9; odf=np.zeros_like(raw)
        for i,v in enumerate(raw): env=max(v,env*det.decay,1e-9); odf[i]=v/env
    else: odf=raw
    out=[]
    for i in range(2,len(odf)-2):
        past=odf[max(0,i-det.med_len):i]
        thr=np.median(past)*det.mult+(det.delta if norm else det.delta*det._scale(odf,i))
        if odf[i]>=odf[i-1] and odf[i]>=odf[i+1] and odf[i]>=odf[i+2] and odf[i]>0:
            out.append(((i*det.hop+det.frame/2)/det.sr, odf[i]/max(thr,1e-9)))
    return out
rows=[]
for name,det,norm in [('flux',dsp.SpectralFlux(SR,delta=0.3,mult=1.5),False),('norm',dsp.NormFlux(SR,delta=0.15,mult=1.3),True)]:
    for g in [1.0,0.8,0.6,0.5]:
        F={'strum':[],'pluck':[]}; Fb={}; D=[]
        for kind,bpm,x,tt in data:
            cand=candidates(det,x,norm)
            acc=[]; last=-1
            for t,r in cand:
                near=np.min(np.abs(tt-t))<=0.07
                if (r>=1.0 or (near and r>=g)) and t-last>=0.06: acc.append(t); last=t
            P,R,f,d=match(np.array(acc),tt); F[kind].append(f); Fb.setdefault(bpm,[]).append(f); D+=list(d*1000)
        D=np.array(D)
        row=dict(algo=name,guided_ratio=g,F_strum=float(np.mean(F['strum'])),F_pluck=float(np.mean(F['pluck'])),sd=float(D.std()),F_by_bpm={k:round(float(np.mean(v)),3) for k,v in Fb.items()})
        rows.append(row); print(row,flush=True)
json.dump(rows,open('results_onset_guided.json','w'),indent=1)
