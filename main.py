"""Запуск: python main.py. Android: buildozer android debug."""
import json
import os
from pathlib import Path

if __name__ == '__main__':
    from bootstrap import ensure_runtime
    ensure_runtime()

from kivy.config import Config
if not os.environ.get('ANDROID_ARGUMENT'):
    Config.set('graphics', 'width', '520')
    Config.set('graphics', 'height', '820')
if os.environ.get('OKNOMER_SCREENSHOT'):
    Config.set('graphics', 'width', '480')
    Config.set('graphics', 'height', '900')
from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.core.text import Label as CoreLabel
from kivy.graphics import Color, Rectangle, RoundedRectangle, Line, Ellipse
from kivy.graphics.texture import Texture
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.filechooser import FileChooserListView
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.screenmanager import Screen, ScreenManager, NoTransition
from kivy.uix.scrollview import ScrollView
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput
from kivy.uix.widget import Widget
from kivy.utils import platform
import numpy as np
from PIL import Image, ImageOps
from oknomer.geometry import measure, detect_a4, analyze_photo
from threading import Thread
from oknomer.storage import Store
from oknomer.platform_io import AndroidIO

BG = (.95, .965, .96, 1)
PANEL = (.88, .93, .92, 1)
TEAL = (.04, .43, .36, 1)
BLUE = (.20, .43, .90, 1)
TEXT = (.10, .19, .18, 1)
MUTED = (.36, .44, .42, 1)


def label(text, height=44, size=15):
    obj = Label(text=text, color=TEXT, font_size=dp(size), size_hint_y=None,
                height=dp(height), halign='left', valign='middle')
    obj.bind(size=lambda w, s: setattr(w, 'text_size', (s[0], s[1])))
    return obj


def button(text, callback, primary=False, height=46):
    obj = Button(text=text, size_hint_y=None, height=dp(height), font_size=dp(14),
                 background_normal='', background_down='', background_color=(0, 0, 0, 0),
                 color=(1, 1, 1, 1) if primary else TEXT)
    with obj.canvas.before:
        tint = Color(*(TEAL if primary else PANEL))
        shape = RoundedRectangle(pos=obj.pos, size=obj.size, radius=[dp(12)])
    obj.bind(pos=lambda w, v: setattr(shape, 'pos', v), size=lambda w, v: setattr(shape, 'size', v))
    obj.bind(state=lambda w, v: setattr(tint, 'a', .72 if v=='down' else 1))
    obj.bind(on_release=callback)
    return obj


def field(hint, text=''):
    return TextInput(text=text, hint_text=hint, multiline=False, size_hint_y=None,
                     height=dp(45), padding=[dp(12), dp(12)], font_size=dp(15),
                     background_normal='', background_active='', background_color=(1, 1, 1, 1),
                     foreground_color=TEXT, cursor_color=TEAL)


class PhotoCanvas(Widget):
    def __init__(self, changed, **kwargs):
        super().__init__(**kwargs)
        self.changed = changed
        self.texture = None
        self.rgb = None
        self.points = {'opening': [], 'reference': []}
        self.active = 'opening'
        self.drag = None
        self.readonly = False
        self.bind(pos=self.redraw, size=self.redraw)

    def set_photo(self, rgb):
        self.rgb = rgb
        h, w = rgb.shape[:2]
        self.texture = Texture.create(size=(w, h), colorfmt='rgb')
        self.texture.blit_buffer(rgb.tobytes(), colorfmt='rgb', bufferfmt='ubyte')
        self.texture.flip_vertical()
        self.points = {'opening': [], 'reference': []}
        self.redraw()

    def image_box(self):
        if self.texture is None:
            return self.x, self.y, 1, 1, 1
        iw, ih = self.texture.size
        scale = min(self.width/iw, self.height/ih)
        return self.x+(self.width-iw*scale)/2, self.y+(self.height-ih*scale)/2, iw*scale, ih*scale, scale

    def to_screen(self, point):
        x, y, w, h, scale = self.image_box()
        return x+point[0]*scale, y+h-point[1]*scale

    def to_image(self, pos):
        x, y, w, h, scale = self.image_box()
        if not (x <= pos[0] <= x+w and y <= pos[1] <= y+h):
            return None
        iw, ih = self.texture.size
        return [min(iw-1, max(0., (pos[0]-x)/scale)), min(ih-1, max(0., (y+h-pos[1])/scale))]

    def redraw(self, *args):
        self.canvas.clear()
        with self.canvas:
            Color(*PANEL)
            Rectangle(pos=self.pos, size=self.size)
            if self.texture is None:
                text = CoreLabel(text='Добавьте фотографию\nПроём и лист А4 должны быть видны целиком',
                                 font_size=dp(14), color=MUTED, halign='center')
                text.refresh()
                Color(1, 1, 1, 1)
                Rectangle(texture=text.texture, pos=(self.center_x-text.texture.width/2,
                          self.center_y-text.texture.height/2), size=text.texture.size)
                return
            x, y, w, h, scale = self.image_box()
            Color(1, 1, 1, 1)
            Rectangle(texture=self.texture, pos=(x, y), size=(w, h))
            for kind, points in self.points.items():
                color = BLUE if kind == 'opening' else TEAL
                coords = [self.to_screen(p) for p in points]
                Color(*color)
                if len(coords) > 1:
                    Line(points=[v for p in coords for v in p], close=len(coords)==4, width=dp(1.5))
                for i, (px, py) in enumerate(coords):
                    Color(*color)
                    Ellipse(pos=(px-dp(6), py-dp(6)), size=(dp(12), dp(12)))
                    text = CoreLabel(text=str(i+1), font_size=dp(15), color=(1,1,1,1))
                    text.refresh()
                    Color(0, 0, 0, .8)
                    Rectangle(pos=(px+dp(7), py+dp(5)), size=text.texture.size)
                    Color(1, 1, 1, 1)
                    Rectangle(texture=text.texture, pos=(px+dp(7), py+dp(5)), size=text.texture.size)
            if self.drag is not None:
                point = self.points[self.active][self.drag]
                px, py = map(int, point)
                # Лупа по оригинальным пикселям помогает уточнять маленькие углы.
                patch = Image.fromarray(self.rgb).crop((px-24, py-24, px+24, py+24)).resize((144, 144))
                tex = Texture.create(size=patch.size, colorfmt='rgb')
                tex.blit_buffer(patch.tobytes(), colorfmt='rgb', bufferfmt='ubyte')
                tex.flip_vertical()
                lx, ly = self.right-dp(150), self.top-dp(150)
                Color(1, 1, 1, 1)
                Rectangle(texture=tex, pos=(lx, ly), size=(dp(144), dp(144)))
                Color(1, .3, .2, 1)
                Line(points=[lx+dp(72), ly+dp(60), lx+dp(72), ly+dp(84)], width=1)
                Line(points=[lx+dp(60), ly+dp(72), lx+dp(84), ly+dp(72)], width=1)

    def on_touch_down(self, touch):
        if self.readonly or self.texture is None or not self.collide_point(*touch.pos):
            return super().on_touch_down(touch)
        point = self.to_image(touch.pos)
        if point is None:
            return False
        points = self.points[self.active]
        distances = [np.linalg.norm(np.asarray(self.to_screen(p))-touch.pos) for p in points]
        if distances and min(distances) < dp(26):
            self.drag = int(np.argmin(distances))
        elif len(points) < 4:
            points.append(point)
            self.drag = len(points)-1
        else:
            return True
        touch.grab(self)
        self.changed()
        self.redraw()
        return True

    def on_touch_move(self, touch):
        if touch.grab_current is self and self.drag is not None:
            point = self.to_image(touch.pos)
            if point is not None:
                self.points[self.active][self.drag] = point
                self.changed()
                self.redraw()
            return True
        return super().on_touch_move(touch)

    def on_touch_up(self, touch):
        if touch.grab_current is self:
            touch.ungrab(self)
            self.drag = None
            self.redraw()
            return True
        return super().on_touch_up(touch)


class OknoMerApp(App):
    title = 'ОкноМер'

    def build(self):
        Window.clearcolor = BG
        self.store = Store(self.user_data_dir)
        self.io = AndroidIO(self)
        self.object_id = None
        self.object_name = ''
        self.photo_path = None
        self.result = None
        self.auto_confirmed = True
        self.manager = ScreenManager(transition=NoTransition())
        self.home()
        Window.bind(on_keyboard=self.back_key)
        return self.manager

    def action(self, function):
        def run(*args):
            try:
                return function()
            except Exception as e:
                self.notice(str(e))
        return run

    def notice(self, message, title='ОкноМер'):
        layout = BoxLayout(orientation='vertical', padding=dp(16), spacing=dp(12))
        text = Label(text=message, color=TEXT, font_size=dp(15), halign='left', valign='middle')
        text.bind(size=lambda w, s: setattr(w, 'text_size', (s[0], None)))
        layout.add_widget(text)
        popup = Popup(title=title, content=layout, size_hint=(.94, .55),
                      background='', background_color=BG, title_color=TEXT)
        layout.add_widget(button('Понятно', lambda *_: popup.dismiss(), True))
        popup.open()

    def page(self, name, title, back=None):
        if self.manager.has_screen(name):
            self.manager.remove_widget(self.manager.get_screen(name))
        screen = Screen(name=name)
        root = BoxLayout(orientation='vertical', padding=dp(14), spacing=dp(9))
        with root.canvas.before:
            Color(*BG)
            background = Rectangle(pos=root.pos, size=root.size)
        root.bind(pos=lambda w, value: setattr(background, 'pos', value),
                  size=lambda w, value: setattr(background, 'size', value))
        top = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(10))
        if back:
            b = button('Назад', self.action(back)); b.size_hint_x = .28
            top.add_widget(b)
        top.add_widget(label(title, 46, 22))
        root.add_widget(top)
        screen.add_widget(root)
        self.manager.add_widget(screen)
        self.manager.current = name
        return root

    def list_area(self, root):
        scroll = ScrollView()
        content = BoxLayout(orientation='vertical', size_hint_y=None, spacing=dp(8))
        content.bind(minimum_height=content.setter('height'))
        scroll.add_widget(content)
        root.add_widget(scroll)
        return content

    def home(self):
        root = self.page('home', 'ОкноМер')
        root.add_widget(label('Размеры окна или двери — по одному фото', 52, 20))
        root.add_widget(label('Положите рядом лист А4. Приложение найдёт\nграницы и предложит размер.', 60, 15))
        root.add_widget(button('Измерить по фотографии', self.action(self.quick_start), True, 54))
        root.add_widget(button('Попробовать на примере', self.action(self.demo), height=48))
        root.add_widget(label('Мои объекты', 40, 18))
        content = self.list_area(root)
        for obj in self.store.objects():
            count = len(self.store.measurements(obj['id']))
            content.add_widget(button(f"{obj['name']}  ·  замеров: {count}",
                                     self.action(lambda o=obj: self.open_object(o['id'], o['name'])), height=54))
        if not self.store.objects():
            content.add_widget(label('Здесь появятся сохранённые замеры.', 60, 14))
        name = field('Название нового объекта, например «Квартира»')
        root.add_widget(name)
        root.add_widget(button('Создать объект', self.action(lambda: self.open_object(
            self.store.add_object(name.text), name.text.strip()))))
        root.add_widget(button('Как сделать подходящее фото', lambda *_: self.notice(
            'Закрепите А4 в одной плоскости с границами проёма.\n\n'
            'Снимайте без размытия и сильного наклона. Все четыре угла листа и проёма должны быть видны.\n\n'
            'Автопоиск может выбрать раму или стекло. Проверьте цветные контуры перед сохранением.')))

    def quick_start(self):
        objects = self.store.objects()
        obj = next((o for o in objects if o['name']=='Мои замеры'), None)
        self.object_id = obj['id'] if obj else self.store.add_object('Мои замеры')
        self.object_name = 'Мои замеры'
        self.new_measurement()

    def open_object(self, key, name):
        self.object_id, self.object_name = key, name
        root = self.page('object', 'Объект', self.home)
        root.add_widget(label(name, 45, 18))
        root.add_widget(button('Новый замер', self.action(self.new_measurement), True))
        root.add_widget(button('Экспортировать ZIP с фото и CSV', self.action(self.export)))
        content = self.list_area(root)
        rows = self.store.measurements(key)
        if not rows:
            content.add_widget(label('Сохранённые замеры появятся здесь.', 55))
        for row in rows:
            p = json.loads(row['payload'])['result']
            text = f"{row['name']}\n{p['width_mm']:.0f} × {p['height_mm']:.0f} мм  •  {row['created'][:10]}"
            content.add_widget(button(text, self.action(lambda k=row['id']: self.show_saved(k)), height=68))

    def return_object(self):
        self.open_object(self.object_id, self.object_name)

    def new_measurement(self):
        self.analysis_id = getattr(self, 'analysis_id', 0)+1
        self.photo_path = None
        self.result = None
        self.auto_confirmed = True
        self.opening_candidates = []
        self.candidate_index = 0
        root = self.page('edit', 'Новый замер', self.return_object)
        root = self.list_area(root)
        root.add_widget(label('1  Фото     /     2  Автопоиск     /     3  Размер', 30, 13))
        row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(8))
        row.add_widget(button('Выбрать фото', self.action(self.pick), True, 48))
        row.add_widget(button('Камера', self.action(self.camera), height=48))
        root.add_widget(row)
        self.measure_name = field('Название замера', 'Проём')
        root.add_widget(self.measure_name)
        self.photo = PhotoCanvas(self.invalidate, size_hint_y=None, height=dp(340))
        root.add_widget(self.photo)
        root.add_widget(label('Синий — проём     ·     Зелёный — лист А4', 24, 12))
        self.hint = label('Выберите фото. Границы появятся автоматически.', 58, 13)
        root.add_widget(self.hint)
        self.size_label = label('Размер пока не рассчитан', 48, 23)
        root.add_widget(self.size_label)
        row = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(8))
        row.add_widget(button('Другой контур', self.action(self.next_candidate), height=42))
        row.add_widget(button('Исправить границы', self.action(self.toggle_manual), height=42))
        root.add_widget(row)
        self.manual_box = BoxLayout(orientation='vertical', size_hint_y=None, height=0, spacing=dp(6))
        self.manual_open = False
        root.add_widget(self.manual_box)
        self.orientation = Spinner(text='А4 вертикально: 210 × 297', values=('А4 вертикально: 210 × 297', 'А4 горизонтально: 297 × 210'),
                                   size_hint_y=None, height=dp(40), font_size=dp(13),
                                   background_normal='', background_color=PANEL, color=TEXT)
        self.orientation.bind(text=lambda *_: self.invalidate())
        self.save_button = button('Сохранить замер', self.action(self.save), True, 48)
        self.save_button.disabled = True
        root.add_widget(self.save_button)

    def toggle_manual(self):
        self.manual_open = not self.manual_open
        self.manual_box.clear_widgets()
        self.manual_box.height = dp(132) if self.manual_open else 0
        if not self.manual_open:
            return
        row = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(6))
        row.add_widget(button('Углы проёма', lambda *_: self.set_active('opening'), height=40))
        row.add_widget(button('Углы А4', lambda *_: self.set_active('reference'), height=40))
        row.add_widget(button('Сбросить', self.action(self.reset_points), height=40))
        self.manual_box.add_widget(row)
        self.manual_box.add_widget(self.orientation)
        self.manual_box.add_widget(button('Пересчитать размер', self.action(self.calculate), height=40))
        self.hint.text = 'Выберите контур и двигайте точки. Новые точки:\nсверху слева, далее по часовой стрелке.'

    def next_candidate(self):
        if len(self.opening_candidates) < 2:
            self.notice('Других контуров нет. Нажмите «Исправить границы», чтобы указать углы вручную.')
            return
        self.candidate_index = (self.candidate_index+1) % len(self.opening_candidates)
        self.photo.points['opening'] = [p[:] for p in self.opening_candidates[self.candidate_index]]
        self.photo.redraw()
        self.recalculate()

    def recalculate(self):
        if self.photo.rgb is None:
            return
        try:
            self.result = measure(self.photo.points['opening'], self.photo.points['reference'],
                                  self.photo.texture.size, 'горизонтально' in self.orientation.text)
        except ValueError as exc:
            self.result = None
            self.size_label.text = 'Нужно уточнить границы'
            self.hint.text = str(exc)
            self.save_button.disabled = True
            return
        r = self.result
        self.size_label.text = f"{r['width_mm']:.0f} × {r['height_mm']:.0f} мм"
        self.hint.text = 'Предварительный размер. Проверьте оба контура.'
        if r['warnings']:
            self.hint.text += '\nЧувствительная разметка — лучше снять ближе.'
        self.save_button.disabled = False

    def invalidate(self):
        self.result = None
        if hasattr(self, 'size_label'):
            self.size_label.text = 'Границы изменены'
            self.save_button.disabled = True
            self.hint.text = 'Нажмите «Пересчитать размер» после исправления точек.'

    def set_active(self, kind):
        self.photo.active = kind
        self.invalidate()

    def reset_points(self):
        self.photo.points[self.photo.active] = []
        self.photo.redraw()
        self.invalidate()

    def load_photo(self, path):
        # EXIF поворот применяется до разметки; все дальнейшие координаты стабильны.
        with Image.open(path) as source:
            img = ImageOps.exif_transpose(source).convert('RGB')
            img.thumbnail((2400, 2400))
            if min(img.size) < 100:
                raise ValueError('Фотография слишком маленькая.')
            target = Path(self.user_data_dir)/'current.jpg'
            img.save(target, quality=95)
            rgb = np.asarray(img).copy()
        self.photo_path = str(target)
        self.photo.set_photo(rgb)
        self.auto_confirmed = True
        self.start_analysis()

    def pick(self):
        if platform == 'android':
            self.io.pick()
            return
        if platform == 'win':
            from oknomer.windows_dialog import choose_photo
            initial = getattr(self, 'last_photo_directory', Path.home() / 'Pictures')
            if not Path(initial).is_dir():
                initial = Path.home()
            selected = choose_photo(initial)
            if selected:
                self.load_photo(selected)
                self.last_photo_directory = Path(selected).parent
            return
        content = BoxLayout(orientation='vertical', spacing=dp(8))
        chooser = FileChooserListView(path=str(Path.home()), filters=['*.jpg', '*.jpeg', '*.png', '*.JPG', '*.PNG', '*.webp'])
        content.add_widget(chooser)
        popup = Popup(title='Выберите фотографию', content=content, size_hint=(.96, .9), background='', background_color=BG, title_color=TEXT)
        def choose():
            if not chooser.selection:
                raise ValueError('Выберите файл.')
            self.load_photo(chooser.selection[0])
            popup.dismiss()
        content.add_widget(button('Открыть', self.action(choose), True))
        content.add_widget(button('Отмена', lambda *_: popup.dismiss()))
        popup.open()

    def camera(self):
        if platform == 'android':
            self.io.camera()
        else:
            # На компьютере — снимок с веб-камеры. На телефоне — системная камера.
            import cv2
            cap = cv2.VideoCapture(0)
            try:
                ok, frame = cap.read()
                if not ok:
                    raise ValueError('Веб-камера недоступна. Загрузите фото из файла.')
                target = Path(self.user_data_dir)/'webcam.jpg'
                Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)).save(target)
                self.load_photo(target)
            finally:
                cap.release()

    def start_analysis(self):
        self.analysis_id = getattr(self, 'analysis_id', 0)+1
        token = self.analysis_id
        self.result = None
        self.save_button.disabled = True
        self.size_label.text = 'Ищем границы…'
        self.hint.text = 'Обрабатываем фото. Это займёт несколько секунд.'
        self.photo.readonly = True
        rgb = self.photo.rgb.copy()
        def work():
            try:
                data = analyze_photo(rgb)
            except Exception as exc:
                data = {'reference': None, 'openings': [], 'landscape': False,
                        'result': None, 'error': 'Не удалось обработать фото: '+str(exc)}
            Clock.schedule_once(lambda dt: self.apply_analysis(token, data), 0)
        Thread(target=work, daemon=True).start()

    def apply_analysis(self, token, data):
        if token != self.analysis_id:
            return
        self.photo.readonly = False
        self.opening_candidates = data['openings']
        self.candidate_index = 0
        self.photo.points = {'opening': [p[:] for p in data['openings'][0]] if data['openings'] else [],
                             'reference': data['reference'] or []}
        self.orientation.text = ('А4 горизонтально: 297 × 210' if data['landscape'] else 'А4 вертикально: 210 × 297')
        self.photo.redraw()
        self.recalculate()
        if data['error'] and self.result is None:
            self.hint.text = data['error']
        if not self.result:
            self.size_label.text = 'Нужна ваша помощь'

    def auto_a4(self, silent=False):
        if self.photo.rgb is not None:
            self.start_analysis()

    def confirm_a4(self):
        if len(self.photo.points['reference']) != 4:
            raise ValueError('Сначала укажите четыре угла А4.')
        self.auto_confirmed = True
        self.hint.text = 'А4 подтверждён. Проверьте ориентацию листа.'

    def calculate(self):
        if self.photo_path is None:
            raise ValueError('Сначала выберите фотографию.')
        self.recalculate()

    def result_page(self):
        root = self.page('result', 'Результат', lambda: setattr(self.manager, 'current', 'edit'))
        r = self.result
        content = self.list_area(root)
        content.add_widget(label(f"{r['width_mm']:.0f} × {r['height_mm']:.0f} мм", 88, 32))
        content.add_widget(label('Ширина × высота • коррекция перспективы', 40, 14))
        content.add_widget(label(f"Без коррекции: {r['baseline_width_mm']:.0f} × {r['baseline_height_mm']:.0f} мм", 40))
        content.add_widget(label(f"Чувствительность к сдвигу точек на 1 px: {r['sensitivity_pct']:.1f}%\nЭто модель устойчивости, не погрешность измерения.", 72, 14))
        for warning in r['warnings']:
            content.add_widget(label(warning, 72, 14))
        content.add_widget(label('Эталон: лист А4. Расчёт верен при общей\nплоскости листа и границ проёма.', 70))
        content.add_widget(button('Сохранить замер', self.action(self.save), True))
        content.add_widget(button('Вернуться к разметке', lambda *_: setattr(self.manager, 'current', 'edit')))

    def save(self):
        payload = {'opening': self.photo.points['opening'], 'reference': self.photo.points['reference'],
                   'image_size': list(self.photo.texture.size), 'result': self.result}
        if self.result is None:
            raise ValueError('Сначала выполните расчёт.')
        key = self.store.save(self.object_id, self.measure_name.text, self.photo_path, payload)
        self.show_saved(key)

    def show_saved(self, key):
        row = self.store.get(key)
        root = self.page('saved', 'Сохранённый замер', self.return_object)
        name = field('Название', row['name'])
        root.add_widget(name)
        canvas = PhotoCanvas(lambda: None)
        canvas.readonly = True
        with Image.open(row['photo_path']) as img:
            canvas.set_photo(np.asarray(img.convert('RGB')).copy())
        canvas.points = {'opening': row['payload']['opening'], 'reference': row['payload']['reference']}
        canvas.redraw()
        root.add_widget(canvas)
        r = row['payload']['result']
        root.add_widget(label(f"{r['width_mm']:.0f} × {r['height_mm']:.0f} мм", 60, 28))
        root.add_widget(label('Дата UTC: '+row['created'][:19].replace('T', ' '), 34, 13))
        root.add_widget(button('Сохранить название', self.action(lambda: self.rename(key, name.text)), True))
        root.add_widget(button('Экспортировать объект', self.action(self.export)))
        root.add_widget(button('Удалить замер', lambda *_: self.confirm_delete(key)))

    def rename(self, key, name):
        self.store.rename(key, name)
        self.notice('Название изменено.')

    def confirm_delete(self, key):
        box = BoxLayout(orientation='vertical', padding=dp(12), spacing=dp(10))
        box.add_widget(label('Удалить замер и его фотографию?', 75))
        popup = Popup(title='Удаление замера', content=box, size_hint=(.94, .4), background='', background_color=BG, title_color=TEXT)
        def remove():
            self.store.delete(key)
            popup.dismiss()
            self.return_object()
        box.add_widget(button('Удалить', self.action(remove)))
        box.add_widget(button('Отмена', lambda *_: popup.dismiss(), True))
        popup.open()

    def export(self):
        dest = Path(self.user_data_dir)/('OknoMer_'+self.object_id[:8]+'.zip')
        self.store.export(self.object_id, dest)
        if platform == 'android':
            self.io.export(str(dest))
        else:
            self.notice('Экспорт сохранён:\n'+str(dest)+'\n\nВ архиве: CSV, JSON и фотографии.')

    def demo(self):
        candidates = [o for o in self.store.objects() if o['name']=='Демонстрация — синтетический пример']
        key = candidates[0]['id'] if candidates else self.store.add_object('Демонстрация — синтетический пример')
        self.object_id, self.object_name = key, 'Демонстрация — синтетический пример'
        self.new_measurement()
        self.measure_name.text = 'Учебный пример — автопоиск'
        self.load_photo(Path(__file__).parent/'demo'/'front.jpg')

    def back_key(self, window, key, *args):
        if key == 27 and self.manager.current != 'home':
            if self.manager.current == 'result':
                self.manager.current = 'edit'
            elif self.manager.current == 'object':
                self.home()
            else:
                self.return_object()
            return True
        return False

    def on_pause(self):
        return True

    def on_stop(self):
        self.store.db.close()


if __name__ == '__main__':
    OknoMerApp().run()
