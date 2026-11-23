@echo off
cd /d "%~dp0"
python -m pip install -q -r requirements.txt
where ffmpeg >nul 2>nul || echo Note: ffmpeg not found - install it for merging, MP3 conversion and cover embedding.
python app.py
pause
