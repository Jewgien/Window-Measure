"""Desktop launcher. Uses only the Python standard library before setup."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
CHECK = 'import kivy, numpy, cv2; from PIL import Image'


def environment_python(root=ROOT):
    return root / '.venv' / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')


def launch():
    """Install dependencies into the project environment, then run the app."""
    if os.environ.get('ANDROID_ARGUMENT') or sys.platform == 'android':
        return
    python = environment_python()
    # A subprocess must never start another bootstrap recursively.
    if os.environ.get('OKNOMER_BOOTSTRAPPED') == '1' and Path(sys.prefix).resolve() == (ROOT/'.venv').resolve():
        return
    required = [ROOT/'requirements.txt', ROOT/'oknomer'/'geometry.py', ROOT/'demo'/'front.jpg']
    if any(not path.is_file() for path in required):
        raise RuntimeError('Project files are missing. Extract the WHOLE ZIP, then run START_WINDOWS.bat.')
    print('OknoMer: preparing the application...', flush=True)
    print('Project folder: ' + str(ROOT), flush=True)
    if not python.is_file():
        print('Creating .venv using ' + sys.executable, flush=True)
        subprocess.run([sys.executable, '-m', 'venv', str(ROOT/'.venv')], check=True, cwd=ROOT)
    try:
        check = subprocess.run([str(python), '-c', CHECK], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError as exc:
        raise RuntimeError('The project .venv cannot run. Rename the .venv folder and start again.') from exc
    if check.returncode != 0:
        print('Installing libraries. Internet is required on the first launch.', flush=True)
        # Absolute requirements path: works from any current working directory.
        subprocess.run([str(python), '-m', 'pip', 'install', '-r', str(ROOT/'requirements.txt')], check=True, cwd=ROOT)
        subprocess.run([str(python), '-c', CHECK], check=True, cwd=ROOT)
    env = os.environ.copy()
    env['OKNOMER_BOOTSTRAPPED'] = '1'
    print('Starting OknoMer...', flush=True)
    result = subprocess.run([str(python), str(ROOT/'main.py'), *sys.argv[1:]], cwd=ROOT, env=env)
    raise SystemExit(result.returncode)


def ensure_runtime():
    try:
        launch()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print('\nOknoMer could not start: ' + str(exc), file=sys.stderr)
        print('Copy the error above. Do not install packages into a different Python.', file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == '__main__':
    ensure_runtime()
