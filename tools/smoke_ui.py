"""Desktop smoke test и реальные снимки экрана Kivy с синтетической сценой."""
import os
import sys
import tempfile
from pathlib import Path
os.environ['OKNOMER_SCREENSHOT']='1'
os.environ['KIVY_NO_ARGS']='1'
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from kivy.clock import Clock
from main import OknoMerApp

class SmokeApp(OknoMerApp):
    @property
    def user_data_dir(self):
        if not hasattr(self,'temp_dir'):
            self.temp_dir=tempfile.mkdtemp(prefix='oknomer_smoke_')
        return self.temp_dir

    def on_start(self):
        self.failures=[]
        self.steps=[self.home_shot,self.edit_setup,self.edit_shot,self.calc,
                    self.result_shot,self.manual_shot,self.saved_setup,self.saved_shot,self.small_shot,self.finish]
        self.step_index=0
        Clock.schedule_interval(self.advance,.5)

    def advance(self,dt):
        try:
            self.steps[self.step_index]()
            self.step_index+=1
        except Exception as e:
            self.failures.append(str(e))
            self.stop()
            raise

    def shot(self,name):
        self.manager.export_to_png(str(ROOT/'docs'/name))

    def home_shot(self):
        self.shot('screen_home.png')
    def edit_setup(self):
        self.demo()
    def edit_shot(self):
        self.shot('screen_markup.png')
        # Координаты экрана и фото согласованы после resize/letterboxing.
        import numpy as np
        for p in self.photo.points['opening']:
            assert np.max(abs(np.array(self.photo.to_image(self.photo.to_screen(p)))-p))<1e-6
    def calc(self):
        self.calculate()
        assert self.result is not None
        assert abs(self.result['width_mm']/1200-1)<.10
        assert abs(self.result['height_mm']/1500-1)<.10
    def result_shot(self):
        self.shot('screen_result.png')
    def manual_shot(self):
        self.toggle_manual()
        Clock.schedule_once(lambda dt: self.shot('screen_manual.png'), .1)
    def saved_setup(self):
        self.toggle_manual()
        self.save()
        rows=self.store.measurements(self.object_id)
        assert len(rows)==1
        self.saved_id=rows[0]['id']
    def saved_shot(self):
        self.shot('screen_saved.png')
        self.store.export(self.object_id,Path(self.user_data_dir)/'smoke_export.zip')
    def small_shot(self):
        from kivy.core.window import Window
        Window.size = (480, 600)
        self.manager.current = 'edit'
        self.toggle_manual()
        Clock.schedule_once(lambda dt: self.shot('screen_small.png'), .1)
    def finish(self):
        self.stop()

app=SmokeApp()
app.run()
if app.failures:
    raise SystemExit(1)
print('UI smoke passed: load, map coordinates, calculate, save, reopen, export.')
