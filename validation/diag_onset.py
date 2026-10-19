import numpy as np, dsp, synth
rng=np.random.default_rng(7); SR=48000
chords=list(synth.CHORDS.items())
def seq(bpm=100,n=40):
    step=60/bpm/2; sig=np.zeros(int((n*step+2.5)*SR)); meta=[]; t=0.5
    for i in range(n):
        if rng.random()<0.2 and i>0: t+=step; continue
        name,frets=chords[rng.integers(len(chords))]
        d="D" if i%2==0 else "U"; amp=rng.uniform(0.4,1); sp=rng.uniform(8,40)
        y,h=synth.strum(frets,d,dur=1.5,spread_ms=sp,rng=rng,amp=amp)
        a=int((t+rng.normal(0,0.008))*SR); sig[a:a+len(y)]+=y[:len(sig)-a]; meta.append((a/SR,d,amp,sp,len(h))); t+=step
    return sig,meta
miss=[]; hit=[]; errs=[]
for bpm in [70,100,130,160]:
    s,meta=seq(bpm); x=synth.phone_mic(s,rng=rng,snr_db=rng.uniform(25,40),hp_hz=130)
    det=dsp.SpectralFlux(SR,delta=0.3,mult=1.5); on,odf=det.detect(x)
    ref=[dsp.refine_onset(x,SR,t) for t in on]
    for (t,d,amp,sp,ns) in meta:
        c=[e for e in on if abs(e-t)<0.05]
        if c: hit.append((d,amp,sp,ns)); errs.append((min(c,key=lambda e:abs(e-t))-t)*1000)
        else:
            miss.append((bpm,d,round(amp,2),round(sp),ns))
            # nearest detection
print("hits",len(hit),"miss",len(miss))
print("miss dirs", {k:sum(1 for m in miss if m[1]==k) for k in "DU"}, "hit dirs", {k:sum(1 for m in hit if m[0]==k) for k in "DU"})
print("miss amps mean", np.mean([m[2] for m in miss]) if miss else None, "hit amps", np.mean([h[1] for h in hit]))
print("miss nstrings", [m[4] for m in miss][:30])
print("miss bpm", [m[0] for m in miss])
e=np.array(errs); print("coarse err mean %.1f sd %.1f"%(e.mean(),e.std()))
