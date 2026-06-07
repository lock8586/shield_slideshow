#!/bin/sh
LIB=/volume1/homes/welps/Photos
cd "$LIB" || exit 1

# Build manifest.txt: relative paths to every image, excluding Synology's @eaDir cache.
find . -type f -not -path '*/@eaDir/*' \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.heic' \) | sed 's|^\./||' > manifest.txt
echo "manifest lines: $(wc -l < manifest.txt)"

# (Re)start the static file server on 8080.
pkill -f "http.server 8080" 2>/dev/null
sleep 1
nohup python3 -m http.server 8080 --bind 0.0.0.0 --directory "$LIB" > /tmp/photosrv.log 2>&1 &
sleep 2
echo -n "listening on 8080: "
( ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null ) | grep -q ':8080' && echo YES || echo NO
echo "local manifest fetch: $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/manifest.txt)"
echo "local sample image fetch: $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8080/$(head -1 manifest.txt)")"
