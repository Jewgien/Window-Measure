"""Стандартное окно выбора файла Windows; дополнительных пакетов не требует."""
import ctypes
from ctypes import wintypes
from pathlib import Path
import os


def choose_photo(initial_dir=None):
    if os.name != 'nt':
        raise RuntimeError('Этот диалог предназначен для Windows.')

    class OPENFILENAMEW(ctypes.Structure):
        _fields_ = [
            ('lStructSize', wintypes.DWORD), ('hwndOwner', wintypes.HWND),
            ('hInstance', wintypes.HINSTANCE), ('lpstrFilter', wintypes.LPCWSTR),
            ('lpstrCustomFilter', wintypes.LPWSTR), ('nMaxCustFilter', wintypes.DWORD),
            ('nFilterIndex', wintypes.DWORD), ('lpstrFile', wintypes.LPWSTR),
            ('nMaxFile', wintypes.DWORD), ('lpstrFileTitle', wintypes.LPWSTR),
            ('nMaxFileTitle', wintypes.DWORD), ('lpstrInitialDir', wintypes.LPCWSTR),
            ('lpstrTitle', wintypes.LPCWSTR), ('Flags', wintypes.DWORD),
            ('nFileOffset', wintypes.WORD), ('nFileExtension', wintypes.WORD),
            ('lpstrDefExt', wintypes.LPCWSTR), ('lCustData', wintypes.LPARAM),
            ('lpfnHook', ctypes.c_void_p), ('lpTemplateName', wintypes.LPCWSTR),
            ('pvReserved', ctypes.c_void_p), ('dwReserved', wintypes.DWORD),
            ('FlagsEx', wintypes.DWORD),
        ]

    common = ctypes.WinDLL('comdlg32', use_last_error=True)
    user = ctypes.WinDLL('user32', use_last_error=True)
    common.GetOpenFileNameW.argtypes = [ctypes.POINTER(OPENFILENAMEW)]
    common.GetOpenFileNameW.restype = wintypes.BOOL
    common.CommDlgExtendedError.argtypes = []
    common.CommDlgExtendedError.restype = wintypes.DWORD
    user.GetActiveWindow.argtypes = []
    user.GetActiveWindow.restype = wintypes.HWND
    filename = ctypes.create_unicode_buffer(32768)
    dialog = OPENFILENAMEW()
    dialog.lStructSize = ctypes.sizeof(dialog)
    dialog.hwndOwner = user.GetActiveWindow()
    dialog.lpstrFilter = ('Фотографии\0*.jpg;*.jpeg;*.png;*.webp;*.bmp;*.tif;*.tiff\0'
                          'Все файлы\0*.*\0\0')
    dialog.nFilterIndex = 1
    dialog.lpstrFile = ctypes.cast(filename, wintypes.LPWSTR)
    dialog.nMaxFile = len(filename)
    dialog.lpstrInitialDir = str(initial_dir or Path.home())
    dialog.lpstrTitle = 'ОкноМер — выберите фотографию'
    # Explorer, existing file/path, preserve working directory, no Recent entry.
    dialog.Flags = 0x00080000 | 0x00001000 | 0x00000800 | 0x00000008 | 0x02000000
    if common.GetOpenFileNameW(ctypes.byref(dialog)):
        return filename.value
    error = common.CommDlgExtendedError()
    if error:
        raise OSError(f'Не удалось открыть окно выбора фотографии. Код Windows: 0x{error:04X}')
    return None  # Пользователь нажал «Отмена» или закрыл окно.
