# Welps Picture Slideshow — Project Notes

A self-hosted photo screensaver for the **NVIDIA Shield TV** that streams from a **Synology
NAS** — a full replacement for Google Photos on the TV. Includes the tooling that migrated
the entire photo library off Google.

---

## Data flow

```
Phone  ──(Synology Photos app auto-upload, going forward)──▶  NAS library
                                                                  │
                                          photosrv.sh (HTTP :8080)│
                                                                  ▼
                                   Shield app (PhotoDreamService) ──▶  TV slideshow
```

The NAS is the single source of truth. The Shield streams photos on demand over plain HTTP.

---

## Infrastructure

| Thing | Value |
|---|---|
| NAS | Synology **DS223**, `192.168.1.43`, user `welps` |
| NAS shares | `Media` (mounted `Z:` on PC), home `home`→`Y:`; SSH on 22 |
| Photo library | `/volume1/homes/welps/Photos` (Synology Photos **Personal Space**, organized `YYYY/MM`) |
| Photo server | `python3 -m http.server 8080` serving the library (see `photosrv.sh`) |
| Shield TV | `192.168.1.15`, ADB over `:5555` |
| PC build tools | `ANDROID_HOME=C:\android-sdk`, Gradle `%TEMP%\gradle-8.4`, adb `%TEMP%\platform-tools` |
| NAS password | `C:\Users\jphel\OneDrive\Documents\nas_pw.txt` (read at runtime; never inline it) |

---

## Components

**Android app** (`photos-screensaver/`):
- `SetupActivity.kt` — config UI (NAS URL, slideshow style picker). Launcher + leanback banner = the engagement photo.
- `PhotoDreamService.kt` — the screensaver: streams photos, overlays clock / weather / photo date+location, honors EXIF orientation, weighted/date theme selection.
- `PhotoFetcher.kt` — fetches `manifest.txt`, downloads single photos on demand with a rolling ~40-file cache + prefetch.
- `Theme.kt` — slideshow styles.

**NAS scripts** (in `wifi_debug/`, deployed to `/volume1/homes/welps/`):
- `photosrv.sh` — boot task: serves the library + background-refreshes the manifest.
- `gen_manifest.py` — builds `manifest.txt` as `relpath|YYYYMMDD` (EXIF→filename→folder dates).

**Migration/maintenance tooling** (`wifi_debug/`): `organize.py`, `dedupe_*.ps1`, `extract_images.ps1`, plus the reusable **`add-photos-to-nas` skill** (`~/.claude/skills/add-photos-to-nas/`, bundles `add_photos.py`).

---

## How the library was built

1. **Google Photos Library API is dead** for bulk access (deprecated ~Mar 2025) — so we used **Google Takeout** (≈110 GB, 11 zips).
2. Extracted images to the NAS (sanitizing folder names ending in dots/spaces).
3. **Deduped**: 25,636 → **6,352** unique (removed 19,284 byte-identical copies, ~45.5 GB).
4. Added **1,491** more from `D:\MEDIA\PICTURES` (deduped against the library).
5. **Organized by date** into the Synology Photos library — **7,843 photos** total.
6. JSON sidecars + videos left in `Media/Photos/GooglePhotos` staging as backup.

---

## How the screensaver works

- Fetches `manifest.txt` (`relpath|YYYYMMDD`), shuffles/weights, streams each photo from the NAS on demand (12 s each), prefetching the next.
- Overlays: clock, weather (wttr.in by IP), photo date + reverse-geocoded location (EXIF GPS → Nominatim).
- **Slideshow styles**: Recent Mix (default ½/¼/¼ by age), Latest (recent-heavy), This Month, This Week (±3 days of today, any year). Set in-app; saved to the `theme` config key.

---

## ⚠️ Gotchas / hard-won lessons

- **NordVPN's kill-switch on the Shield silently blocked ALL app LAN traffic** while its tunnel was disconnected — the real cause of endless "can't connect." **Uninstalled it.** Also the likely original Stardew/Moonlight streaming lag. If reinstalled: disable kill-switch / allow LAN.
- **Android blocks cleartext HTTP by default** → app needs `android:usesCleartextTraffic="true"`.
- **Windows/SMB reject folder/file names ending in a dot or space** (e.g. Google's `1 year since...`) — sanitize on extract.
- **HEIC EXIF** isn't right after the `Exif\0\0` marker — search for the TIFF header (`II*` / `MM*`) directly.
- Run long jobs as **tracked background tasks** and report when done.

---

## Operating it

- **Build + deploy:** `gradle assembleDebug` then `adb -s 192.168.1.15:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
- **Add photos:** use the `add-photos-to-nas` skill (copies to NAS, dedupes vs library, files by date).
- **Server persistence:** DSM **Task Scheduler → Triggered → Boot-up** (owner root) runs `sh /volume1/homes/welps/photosrv.sh`.
- **Config via ADB:** `am start -n com.example.photossaver/.SetupActivity --es nas_url "http://192.168.1.43:8080/" --es theme this_week`
- **Self-test:** `... --ez selftest true` then `adb logcat -s PSSELFTEST`

---

## Future ideas

- **People / Subject themes** via Synology Photos web API. Recognition is enabled (Personal Space) and done — **80 face groups** detected (name them in the app first). API: log in `SYNO.API.Auth` (session `Photo`) → `SYNO.Foto.Browse.Person`. Recognition data lives in the root-owned `synofoto` PostgreSQL DB but is readable via the API with the `welps` login (no root).
- "On This Day" (exact date) and specific-year styles.
- Going forward, the Synology Photos phone app should auto-upload new photos into the library so the slideshow stays current automatically.
