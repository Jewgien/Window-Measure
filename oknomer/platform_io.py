"""Android SAF: выбор фото и экспорт без доступа ко всей памяти устройства."""
import os
import shutil
from pathlib import Path
from kivy.clock import Clock
from kivy.utils import platform


class AndroidIO:
    PICK, CAMERA, EXPORT = 4101, 4102, 4103

    def __init__(self, app):
        self.app = app
        self.pending_uri = None
        self.export_path = None
        if platform == 'android':
            from android import activity
            from jnius import autoclass
            self.Intent = autoclass('android.content.Intent')
            self.activity = autoclass('org.kivy.android.PythonActivity').mActivity
            self.resolver = self.activity.getContentResolver()
            activity.bind(on_activity_result=self.on_result)

    def pick(self):
        intent = self.Intent(self.Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(self.Intent.CATEGORY_OPENABLE)
        intent.setType('image/*')
        self.activity.startActivityForResult(intent, self.PICK)

    def camera(self):
        from android.permissions import Permission, check_permission, request_permissions
        if check_permission(Permission.CAMERA):
            self._camera()
        else:
            def granted(permissions, grants):
                Clock.schedule_once(lambda dt: self._camera() if all(grants) else self.app.notice('Нет доступа к камере.'), 0)
            request_permissions([Permission.CAMERA], granted)

    def _camera(self):
        from jnius import autoclass
        try:
            values = autoclass('android.content.ContentValues')()
            values.put('_display_name', 'OknoMer_capture.jpg')
            values.put('mime_type', 'image/jpeg')
            # min API 29: создаваем снимок через MediaStore без WRITE_EXTERNAL_STORAGE.
            values.put('relative_path', 'Pictures/OknoMer')
            images = autoclass('android.provider.MediaStore$Images$Media')
            self.pending_uri = self.resolver.insert(images.EXTERNAL_CONTENT_URI, values)
            if self.pending_uri is None:
                raise RuntimeError('Не удалось создать файл фотографии.')
            intent = self.Intent('android.media.action.IMAGE_CAPTURE')
            intent.putExtra('output', self.pending_uri)
            intent.addFlags(self.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | self.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clip = autoclass('android.content.ClipData')
            intent.setClipData(clip.newRawUri('capture', self.pending_uri))
            self.activity.startActivityForResult(intent, self.CAMERA)
        except Exception as e:
            self.cleanup_camera()
            self.app.notice('Камера недоступна: '+str(e))

    def cleanup_camera(self):
        if self.pending_uri is not None:
            try:
                self.resolver.delete(self.pending_uri, None, None)
            finally:
                self.pending_uri = None

    def export(self, path):
        self.export_path = path
        intent = self.Intent(self.Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(self.Intent.CATEGORY_OPENABLE)
        intent.setType('application/zip')
        intent.putExtra(self.Intent.EXTRA_TITLE, Path(path).name)
        self.activity.startActivityForResult(intent, self.EXPORT)

    def on_result(self, request, result, intent):
        if request not in (self.PICK, self.CAMERA, self.EXPORT):
            return
        Clock.schedule_once(lambda dt: self._handle_result(request, result, intent), 0)

    def _handle_result(self, request, result, intent):
        try:
            if result != -1:
                return
            uri = self.pending_uri if request == self.CAMERA else intent.getData()
            if uri is None:
                raise ValueError('Приложение не вернуло фотографию.')
            descriptor = self.resolver.openFileDescriptor(uri, 'w' if request == self.EXPORT else 'r')
            try:
                with os.fdopen(os.dup(descriptor.getFd()), 'wb' if request == self.EXPORT else 'rb') as stream:
                    if request == self.EXPORT:
                        with open(self.export_path, 'rb') as source:
                            shutil.copyfileobj(source, stream)
                        self.app.notice('Архив экспортирован в выбранную папку.')
                    else:
                        target = Path(self.app.user_data_dir)/'imported_photo'
                        with target.open('wb') as dest:
                            shutil.copyfileobj(stream, dest)
                        self.app.load_photo(target)
            finally:
                descriptor.close()
        except Exception as e:
            self.app.notice('Не удалось обработать файл: '+str(e))
        finally:
            if request == self.CAMERA:
                self.cleanup_camera()
