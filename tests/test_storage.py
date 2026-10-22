import json
from zipfile import ZipFile
from oknomer.storage import Store
from PIL import Image

def test_persistence_export_and_delete(tmp_path):
    photo=tmp_path/'source.jpg'
    Image.new('RGB',(100,100)).save(photo)
    store=Store(tmp_path/'data')
    oid=store.add_object('Квартира')
    payload={'result':dict(width_mm=1200,height_mm=1500,sensitivity_pct=2), 'opening':[],'reference':[]}
    key=store.save(oid,'Окно',photo,payload)
    store.db.close()
    photo.unlink()  # Фото должно сохраниться независимо от исходника.
    store=Store(tmp_path/'data')
    assert store.get(key)['payload']==payload
    store.rename(key,'Кухня')
    assert store.get(key)['name']=='Кухня'
    destination=tmp_path/'export.zip'
    store.export(oid,destination)
    with ZipFile(destination) as z:
        assert 'measurements.csv' in z.namelist()
        assert 'Кухня' in z.read('measurements.csv').decode('utf-8-sig')
        assert len(json.loads(z.read('measurements.json')))==1
        assert len([n for n in z.namelist() if n.startswith('photos/')])==1
    store.delete(key)
    assert store.measurements(oid)==[]
    assert list((tmp_path/'data'/'photos').glob('*'))==[]
