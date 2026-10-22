import json
from pathlib import Path
import numpy as np
import pytest
from PIL import Image
from oknomer.geometry import measure, MeasurementError, detect_a4
ROOT = Path(__file__).resolve().parents[1]

@pytest.mark.parametrize('name',['front','angle_small','angle_large'])
def test_projective_recovery(name):
    d=json.loads((ROOT/'demo'/f'{name}.json').read_text())
    r=measure(d['opening'],d['reference'],d['image_size'],quality=False)
    assert abs(r['width_mm']-1200)<.2
    assert abs(r['height_mm']-1500)<.2

@pytest.fixture
def data():
    return json.loads((ROOT/'demo'/'front.json').read_text())

@pytest.mark.parametrize('points', [[], [[0,0]]*4, [[10,10],[500,500],[500,10],[10,500]], [[1,1],[2,2],[3,3],[4,4]]])
def test_bad_points(data,points):
    with pytest.raises(MeasurementError):
        measure(points,data['reference'],data['image_size'])

def test_missing_reference(data):
    with pytest.raises(MeasurementError):
        measure(data['opening'],[],data['image_size'])

def test_tiny_reference(data):
    ref=np.asarray(data['reference']); ref=(ref-ref[0])*.2+ref[0]
    with pytest.raises(MeasurementError):
        measure(data['opening'],ref,data['image_size'])

def test_outside(data):
    data['opening'][0]=[-1,100]
    with pytest.raises(MeasurementError):
        measure(data['opening'],data['reference'],data['image_size'])

def test_orientation_is_explicit_user_input(data):
    # По одной плоской фотографии ошибочную ориентацию не всегда можно выявить.
    r=measure(data['opening'],data['reference'],data['image_size'],landscape=True)
    assert abs(r['width_mm']-1200*297/210)<.2
    assert abs(r['height_mm']-1500*210/297)<.2

def test_landscape():
    r=measure([[500,200],[1700,200],[1700,1700],[500,1700]],
              [[100,600],[397,600],[397,810],[100,810]],(2000,2000),landscape=True)
    assert r['width_mm']==1200 and r['height_mm']==1500

def test_detection(data):
    found=detect_a4(np.asarray(Image.open(ROOT/'demo'/'front.jpg')))
    assert found is not None
    assert np.max(np.linalg.norm(np.asarray(found)-data['reference'],axis=1))<4

def test_no_detection():
    assert detect_a4(np.zeros((500,500,3),np.uint8)) is None

def test_quality(data):
    r=measure(data['opening'],data['reference'],data['image_size'])
    assert r['width_mm']==1200
    assert r['sensitivity_pct']>=0
