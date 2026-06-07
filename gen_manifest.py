#!/usr/bin/env python3
# Generate manifest.txt for the screensaver: one line per image, "relpath|YYYYMMDD".
# Date = EXIF DateTimeOriginal (JPEG + HEIC) -> date in filename -> YYYY/MM folder (day 00).
import os, struct, re

LIB = "/volume1/homes/welps/Photos"
IMG_EXT = {'.jpg', '.jpeg', '.png', '.heic', '.gif'}

def _parse_tiff(data, tiff):
    bo = data[tiff:tiff+2]
    endian = '<' if bo == b'II' else '>' if bo == b'MM' else None
    if not endian:
        return None
    try:
        u16 = lambda o: struct.unpack(endian+'H', data[o:o+2])[0]
        u32 = lambda o: struct.unpack(endian+'I', data[o:o+4])[0]
        if u16(tiff+2) != 42:
            return None
        def find(ifd, tag):
            n = u16(ifd)
            if n > 1000:
                return None
            for i in range(n):
                e = ifd + 2 + i*12
                if u16(e) == tag:
                    return e
            return None
        ifd0 = tiff + u32(tiff+4)
        for ptr, dt in ((0x8769, 0x9003), (None, 0x0132)):
            ifd = ifd0
            if ptr is not None:
                p = find(ifd0, ptr)
                if not p:
                    continue
                ifd = tiff + u32(p+8)
            e = find(ifd, dt)
            if e:
                v = data[tiff+u32(e+8):tiff+u32(e+8)+19].decode('ascii', 'ignore')
                m = re.match(r'(\d{4}):(\d{2}):(\d{2})', v)
                if m and m.group(1) != '0000':
                    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except Exception:
        return None
    return None

def exif_ymd(path):
    try:
        with open(path, 'rb') as f:
            data = f.read(1048576)
    except Exception:
        return None
    for magic in (b'MM\x00\x2a', b'II\x2a\x00'):
        start = 0; tries = 0
        while tries < 60:
            i = data.find(magic, start)
            if i < 0:
                break
            r = _parse_tiff(data, i)
            if r:
                return r
            start = i + 1; tries += 1
    return None

def filename_ymd(fn):
    m = re.search(r'(20\d{2})[-_]?(\d{2})[-_]?(\d{2})', fn)
    if m:
        y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if 1 <= mo <= 12 and 1 <= d <= 31:
            return (y, mo, d)
    return None

def path_ymd(rel):
    parts = rel.split('/')
    y = int(parts[0]) if parts and parts[0].isdigit() else 0
    mo = 0
    if len(parts) >= 2 and parts[1].isdigit() and 1 <= int(parts[1]) <= 12:
        mo = int(parts[1])
    return (y, mo, 0)

out = []
for root, _, files in os.walk(LIB):
    if '@eaDir' in root:
        continue
    for fn in files:
        if os.path.splitext(fn)[1].lower() not in IMG_EXT:
            continue
        p = os.path.join(root, fn)
        rel = os.path.relpath(p, LIB).replace('\\', '/')
        y, mo, d = exif_ymd(p) or filename_ymd(fn) or path_ymd(rel)
        out.append(f"{rel}|{y:04d}{mo:02d}{d:02d}")

tmp = os.path.join(LIB, "manifest.txt.tmp")
with open(tmp, 'w') as f:
    f.write("\n".join(out) + "\n")
os.replace(tmp, os.path.join(LIB, "manifest.txt"))
print(f"manifest: {len(out)} photos")
