import csv
import io
import json
import shutil
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED


class Store:
    def __init__(self, root):
        self.root = Path(root)
        (self.root/'photos').mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.root/'oknomer.sqlite3')
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS objects(id TEXT PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS measurements(
          id TEXT PRIMARY KEY, object_id TEXT NOT NULL REFERENCES objects(id),
          name TEXT NOT NULL, created TEXT NOT NULL, photo TEXT NOT NULL,
          payload TEXT NOT NULL);
        ''')
        self.db.commit()

    def objects(self):
        return [dict(r) for r in self.db.execute('SELECT * FROM objects ORDER BY rowid DESC')]

    def add_object(self, name):
        name = name.strip()
        if not name:
            raise ValueError('Введите название объекта.')
        key = uuid.uuid4().hex
        with self.db:
            self.db.execute('INSERT INTO objects VALUES (?,?)', (key, name))
        return key

    def save(self, object_id, name, photo, payload):
        if not name.strip():
            raise ValueError('Введите название замера.')
        key = uuid.uuid4().hex
        filename = key+'.jpg'
        shutil.copy2(photo, self.root/'photos'/filename)
        try:
            with self.db:
                self.db.execute('INSERT INTO measurements VALUES (?,?,?,?,?,?)',
                                (key, object_id, name.strip(), datetime.now(timezone.utc).isoformat(),
                                 filename, json.dumps(payload, ensure_ascii=False)))
        except Exception:
            (self.root/'photos'/filename).unlink(missing_ok=True)
            raise
        return key

    def measurements(self, object_id):
        return [dict(r) for r in self.db.execute('SELECT * FROM measurements WHERE object_id=? ORDER BY created DESC', (object_id,))]

    def get(self, key):
        row = self.db.execute('SELECT * FROM measurements WHERE id=?', (key,)).fetchone()
        if row is None:
            raise ValueError('Замер не найден.')
        result = dict(row)
        result['payload'] = json.loads(result['payload'])
        result['photo_path'] = str(self.root/'photos'/result['photo'])
        return result

    def rename(self, key, name):
        if not name.strip():
            raise ValueError('Введите название.')
        with self.db:
            self.db.execute('UPDATE measurements SET name=? WHERE id=?', (name.strip(), key))

    def delete(self, key):
        item = self.get(key)
        with self.db:
            self.db.execute('DELETE FROM measurements WHERE id=?', (key,))
        Path(item['photo_path']).unlink(missing_ok=True)

    def export(self, object_id, destination):
        rows = [self.get(row['id']) for row in self.measurements(object_id)]
        obj = self.db.execute('SELECT name FROM objects WHERE id=?', (object_id,)).fetchone()
        if obj is None:
            raise ValueError('Выберите объект.')
        stream = io.StringIO(newline='')
        writer = csv.writer(stream, delimiter=';')
        writer.writerow(['Объект', 'Название', 'Дата UTC', 'Ширина мм', 'Высота мм', 'Чувствительность %', 'Фото'])
        for row in rows:
            p = row['payload']['result']
            # Не превращать пользовательские названия в формулы Excel.
            safe = lambda v: "'"+v if str(v).startswith(('=', '+', '-', '@')) else v
            writer.writerow([safe(obj['name']), safe(row['name']), row['created'], p['width_mm'], p['height_mm'], p['sensitivity_pct'], row['photo']])
        with ZipFile(destination, 'w', ZIP_DEFLATED) as z:
            z.writestr('measurements.csv', '\ufeff'+stream.getvalue())
            data = [{k: v for k, v in row.items() if k != 'photo_path'} for row in rows]
            z.writestr('measurements.json', json.dumps(data, ensure_ascii=False, indent=2))
            for row in rows:
                z.write(row['photo_path'], 'photos/'+row['photo'])
        return str(destination)
