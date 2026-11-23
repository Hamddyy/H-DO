#!/usr/bin/env sh
# H-DO launcher (Linux / macOS / Termux)
cd "$(dirname "$0")" || exit 1
python3 -m pip install -q -r requirements.txt 2>/dev/null || python3 -m pip install -q --break-system-packages -r requirements.txt
command -v ffmpeg >/dev/null || echo "Note: ffmpeg not found - install it for merging, MP3 conversion and cover embedding."
exec python3 app.py
