# Desktop scanner (Windows / local Python)

Stdlib-only Python server plus the local web UI. No pip packages.

## Start

From this folder:

```bat
python server.py
```

If `python` is not found:

```bat
py server.py
```

Or double-click `start.bat`.

Then open **http://127.0.0.1:3847** and click **Play all**.

Stop the server with **Ctrl+C** in the terminal.

## Portable zip folder (Windows)

1. Copy the whole `desktop/` folder (or unzip a zip of this folder) anywhere — Downloads, a USB drive, the desktop.
2. You only need **Python 3.10+** installed (the “py launcher” from python.org is enough).
3. Double-click `start.bat`, or run `python server.py` / `py server.py` from this folder.
4. Browse to http://127.0.0.1:3847

No installer, no Node, no virtualenv. PyInstaller packaging is optional later if you want a single `.exe`.

## Default feeds

Same order as the Android app. **Nevada / Washoe** is the top group; **California / NEU–TNF** stays below.

| Group | Kind | ID | Name |
|----|------|----|------|
| Nevada / Washoe | Calls (in-app) | TG 30433 | NSRS Washoe TMFPD Red Dispatch |
| Nevada / Washoe | Calls (in-app) | TG 30434 | TMFPD Command 1 |
| Nevada / Washoe | Calls (in-app) | TG 30435 | TMFPD Command 2 |
| Nevada / Washoe | Calls (in-app) | TG 30436 | TMFPD Tac 4 |
| Nevada / Washoe | Calls (in-app) | TG 30437 | TMFPD Tac 5 |
| Nevada / Washoe | Calls (in-app) | TG 30438 | TMFPD Tac 6 |
| Nevada / Washoe | Listen | 7364 | Reno and Sparks Police and Fire |
| California / NEU–TNF | Listen | 14826 | East Placer / Nevada CAL FIRE NEU (Kings Beach / Truckee) |
| California / NEU–TNF | Listen | 47365 | CAL FIRE NEU West |
| California / NEU–TNF | Listen | 47367 | Tahoe National Forest West |

Calls cards Play in-app. The local server logs in with `BROADCASTIFY_USERNAME` and `BROADCASTIFY_PASSWORD` from the environment (never committed) and proxies each clip. Talkgroups are not sent to `/api/stream`. **Play all** starts listen feeds and Calls together. Use **＋** in the header to add another listen feed by numeric Broadcastify feed ID.

See the repo root README for HLS/JWT details, preroll, and scanner-awareness notes.
