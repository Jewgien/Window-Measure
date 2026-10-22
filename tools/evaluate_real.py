"""python tools/evaluate_real.py path/to/real_results.csv

CSV (;): id;true_width;true_height;baseline_width;baseline_height;corrected_width;corrected_height
Все размеры в мм. Не включайте пустые строки испытаний или синтетические сцены.
"""
import argparse
import csv
import json
from pathlib import Path
from statistics import mean
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('csv_path',type=Path)
args=p.parse_args()
with args.csv_path.open(encoding='utf-8-sig',newline='') as f:
    rows=list(csv.DictReader(f,delimiter=';'))
results=[]
for row in rows:
    if not row.get('true_width') or not row.get('true_height'):
        continue
    out={'id':row['id']}
    for axis in ('width','height'):
        true=float(row['true_'+axis])
        if true<=0:
            raise ValueError('Контрольный размер должен быть положительным.')
        for method in ('baseline','corrected'):
            if not row.get(method+'_'+axis):
                raise ValueError('Незаполненные измерения в строке '+row['id'])
            error=abs(float(row[method+'_'+axis])-true)
            out[method+'_'+axis+'_error_mm']=round(error,2)
            out[method+'_'+axis+'_error_pct']=round(error/true*100,3)
    results.append(out)
if not results:
    raise SystemExit('Нет заполненных реальных измерений. Результаты не созданы.')
summary={'count':len(results),'measurements':results}
for method in ('baseline','corrected'):
    summary[method+'_mean_error_pct']=round(mean(row[method+'_'+axis+'_error_pct'] for row in results for axis in ('width','height')),3)
out=args.csv_path.with_name(args.csv_path.stem+'_evaluated.json')
out.write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(out)
