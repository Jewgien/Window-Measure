[app]
title = OknoMer
package.name = oknomer
package.domain = org.student
source.dir = .
source.include_exts = py,kv,png,jpg,json,ttf
source.exclude_dirs = tests,tools,docs,.git,.venv,.buildenv,venv,output
version = 0.2.0
requirements = python3,kivy==2.3.1,numpy,opencv,pillow,pyjnius
orientation = portrait
fullscreen = 0
android.permissions = CAMERA
android.api = 35
android.minapi = 29
android.archs = arm64-v8a
android.accept_sdk_license = True
android.allow_backup = False
[buildozer]
log_level = 2
warn_on_root = 1
