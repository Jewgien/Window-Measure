"""Численные испытания на синтетических координатах и моделируемом шуме."""
import csv
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from oknomer.geometry import measure, MeasurementError
ROOT=Path(__file__).resolve().parents[1]
rows=[]
for name in ('front','angle_small','angle_large'):
    d=json.loads((ROOT/'demo'/f'{name}.json').read_text())
    r=measure(d['opening'],d['reference'],d['image_size'],quality=False)
    rows.append(dict(scene=name,true_width=1200,true_height=1500,
                     baseline_width=r['baseline_width_mm'],baseline_height=r['baseline_height_mm'],
                     corrected_width=r['width_mm'],corrected_height=r['height_mm']))
with (ROOT/'docs'/'perspective_synthetic.csv').open('w',newline='',encoding='utf-8-sig') as f:
    w=csv.DictWriter(f,fieldnames=rows[0]); w.writeheader(); w.writerows(rows)
# Этап 14: сравнение исходного расчёта и фильтра качества на одинаковом наборе.
rng=np.random.default_rng(2026)
d=json.loads((ROOT/'demo'/'front.json').read_text())
errors=[];accepted=[];detail=[]
for edge in (40,60,90,150,220):
    scale=edge/147
    for repeat in range(30):
        op=np.asarray(d['opening'])*scale+rng.normal(0,1,(4,2))
        ref=np.asarray(d['reference'])*scale+rng.normal(0,1,(4,2))
        size=(int(1500*scale+20),int(1400*scale+20))
        r=measure(op,ref,size,quality=False)
        error=max(abs(r['width_mm']/1200-1),abs(r['height_mm']/1500-1))*100
        errors.append(error)
        try:
            measure(op,ref,size,quality=True)
            accepted.append(error); ok=True
        except MeasurementError:
            ok=False
        detail.append(dict(marker_edge_px=edge,repeat=repeat,max_relative_error_pct=round(error,4),accepted=ok))
with (ROOT/'docs'/'quality_synthetic.csv').open('w',newline='',encoding='utf-8-sig') as f:
    w=csv.DictWriter(f,fieldnames=detail[0]);w.writeheader();w.writerows(detail)
summary={'synthetic_only':True,'samples':len(errors),'accepted':len(accepted),'rejected':len(errors)-len(accepted),
         'mean_error_before_pct':round(float(np.mean(errors)),3),'mean_error_accepted_pct':round(float(np.mean(accepted)),3),
         'p95_error_before_pct':round(float(np.percentile(errors,95)),3),'p95_error_accepted_pct':round(float(np.percentile(accepted,95)),3),
         'note':'После фильтра сравнивается подмножество принятых снимков. Это отбраковка, а не исправление всех замеров.'}
(ROOT/'docs'/'benchmark_summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
