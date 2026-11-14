@echo off
REM Try the app without building an exe (needs Python 3 on PATH).
setlocal
python -m pip install pystray Pillow keyboard >nul 2>nul
start "" pythonw src\main.py
endlocal
