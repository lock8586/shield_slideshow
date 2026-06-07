#!/bin/sh
# Photo screensaver server: serve the library over HTTP, and refresh the dated
# manifest (relpath|YYYYMMDD) in the background. Runs at NAS boot via Task Scheduler.
LIB=/volume1/homes/welps/Photos
cd "$LIB" || exit 1

pkill -f "http.server 8080" 2>/dev/null
sleep 1

# Refresh the manifest in the background (EXIF scan takes a few minutes); the server
# keeps serving the previous manifest until the new one is atomically swapped in.
python3 /volume1/homes/welps/gen_manifest.py > /tmp/genmanifest.log 2>&1 &

exec python3 -m http.server 8080 --bind 0.0.0.0 --directory "$LIB"
