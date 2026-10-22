"""Синтетические сцены для воспроизводимой проверки; не реальные фотографии."""
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cv2
import numpy as np
from PIL import Image, ImageDraw
from oknomer.geometry import transform

ROOT = Path(__file__).resolve().parents[1]
opening = np.array([[500,180],[1700,180],[1700,1680],[500,1680]], float)*.7+[100,80]
reference = np.array([[150,600],[360,600],[360,897],[150,897]], float)*.7+[100,80]
img = Image.new('RGB', (1500,1400), '#b6b1a7')
d = ImageDraw.Draw(img)
x0,y0 = opening[0]; x1,y1 = opening[2]
d.rectangle((x0-20,y0-20,x1+20,y1+20), fill='#5b6064')
d.rectangle((x0,y0,x1,y1), fill='#364550')
d.rectangle((x0+20,y0+20,x1-20,y1-20), fill='#638b9f')
d.line((int((x0+x1)/2),y0+20,int((x0+x1)/2),y1-20),fill='#d2dce2',width=18)
d.line((x0+20,int(y0+400),x1-20,int(y0+400)),fill='#d2dce2',width=18)
d.rectangle(tuple(reference[0])+tuple(reference[2]),fill='white')
d.text((reference[0][0]+25,reference[0][1]+60), 'A4\n210 x 297', fill='#888888')
d.text((70,35), 'SYNTHETIC TEST SCENE - NOT A REAL PHOTO', fill='#202020')
for name, h in [('front',np.eye(3)), ('angle_small',np.array([[1,.015,0],[0,1,0],[.00012,0,1]])), ('angle_large',np.array([[1,.03,0],[.01,1,0],[.00027,0,1]]))]:
    arr = cv2.warpPerspective(np.asarray(img),h,img.size,borderValue=(50,55,65))
    Image.fromarray(arr).save(ROOT/'demo'/f'{name}.jpg',quality=97)
    data={'synthetic':True,'true_width_mm':1200,'true_height_mm':1500,
          'opening':transform(opening,h).tolist(),'reference':transform(reference,h).tolist(),
          'image_size':list(img.size)}
    (ROOT/'demo'/f'{name}.json').write_text(json.dumps(data,indent=2),encoding='utf-8')
