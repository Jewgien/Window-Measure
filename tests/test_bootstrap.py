from pathlib import Path
from types import SimpleNamespace
import pytest
import bootstrap


def setup(monkeypatch, tmp_path, installed):
    root = tmp_path/'folder with spaces'/'ОкноМер'
    (root/'oknomer').mkdir(parents=True)
    (root/'demo').mkdir()
    for file in ['requirements.txt','oknomer/geometry.py','demo/front.jpg']:
        (root/file).write_text('fixture')
    python = root/'.venv'/'Scripts'/'python.exe'
    if installed:
        python.parent.mkdir(parents=True); python.touch()
    monkeypatch.setattr(bootstrap,'ROOT',root)
    monkeypatch.setattr(bootstrap,'environment_python',lambda: python)
    monkeypatch.delenv('ANDROID_ARGUMENT',raising=False)
    monkeypatch.delenv('OKNOMER_BOOTSTRAPPED',raising=False)
    return root,python


def test_install_uses_project_paths_from_unrelated_cwd(monkeypatch,tmp_path):
    root,python=setup(monkeypatch,tmp_path,False)
    monkeypatch.chdir(tmp_path)
    calls=[]
    def run(args,**kwargs):
        calls.append((args,kwargs))
        return SimpleNamespace(returncode=1 if len(calls)==2 else 0)
    monkeypatch.setattr(bootstrap.subprocess,'run',run)
    with pytest.raises(SystemExit) as e: bootstrap.launch()
    assert e.value.code==0
    assert calls[0][0][-1]==str(root/'.venv')
    assert calls[2][0][-1]==str(root/'requirements.txt')
    assert calls[-1][0][0:2]==[str(python),str(root/'main.py')]
    assert all(kw['cwd']==root for _,kw in calls)
    assert calls[-1][1]['env']['OKNOMER_BOOTSTRAPPED']=='1'


def test_existing_dependencies_skip_install(monkeypatch,tmp_path):
    setup(monkeypatch,tmp_path,True)
    calls=[]
    def run(args,**kwargs): calls.append(args);return SimpleNamespace(returncode=0)
    monkeypatch.setattr(bootstrap.subprocess,'run',run)
    with pytest.raises(SystemExit): bootstrap.launch()
    assert len(calls)==2
    assert not any('pip' in c for c in calls)


def test_missing_archive_files_fail_before_install(monkeypatch,tmp_path):
    root,python=setup(monkeypatch,tmp_path,False)
    (root/'requirements.txt').unlink()
    with pytest.raises(RuntimeError,match='WHOLE ZIP'):bootstrap.launch()


def test_android_skips_desktop_setup(monkeypatch):
    monkeypatch.setenv('ANDROID_ARGUMENT','/app')
    assert bootstrap.launch() is None


def test_install_failure_does_not_start_app(monkeypatch,tmp_path):
    setup(monkeypatch,tmp_path,True)
    calls=[]
    def run(args,**kwargs):
        calls.append(args)
        if 'pip' in args: raise bootstrap.subprocess.CalledProcessError(1,args)
        return SimpleNamespace(returncode=1)
    monkeypatch.setattr(bootstrap.subprocess,'run',run)
    with pytest.raises(SystemExit) as e:bootstrap.ensure_runtime()
    assert e.value.code==1
    assert len(calls)==2
