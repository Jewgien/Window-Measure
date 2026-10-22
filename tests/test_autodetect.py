from pathlib import Path
import numpy as np
from PIL import Image
import pytest
from oknomer.geometry import analyze_photo
ROOT=Path(__file__).resolve().parents[1]

@pytest.mark.parametrize('name',['front','angle_small','angle_large'])
def test_automatic_photo_pipeline(name):
    data=analyze_photo(np.asarray(Image.open(ROOT/'demo'/f'{name}.jpg')))
    assert data['reference'] and data['openings']
    assert data['result'] is not None, data['error']
    # В сцене есть наружная рама: автопоиск может выбрать её вместо внутреннего края.
    assert abs(data['result']['width_mm']/1200-1)<.10
    assert abs(data['result']['height_mm']/1500-1)<.10


def test_empty_image_requires_manual_help():
    d=analyze_photo(np.zeros((800,800,3),np.uint8))
    assert d['result'] is None
    assert not d['reference'] and not d['openings']
    assert d['error']
