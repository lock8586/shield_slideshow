import os, struct, json, re
from datetime import datetime
SRC = "/volume1/Media/Photos/GooglePhotos/Takeout/Google Photos"

def exif_ym(path):
    try:
        with open(path, 'rb') as f:
            data = f.read(1048576)
        idx = data.find(b'Exif\x00\x00')
        if idx < 0:
            return None
        tiff = idx + 6
        bo = data[tiff:tiff+2]
        endian = '<' if bo == b'II' else '>' if bo == b'MM' else None
        if not endian:
            return None
        u16 = lambda o: struct.unpack(endian+'H', data[o:o+2])[0]
        u32 = lambda o: struct.unpack(endian+'I', data[o:o+4])[0]
        def find(ifd, tag):
            for i in range(u16(ifd)):
                e = ifd + 2 + i*12
                if u16(e) == tag:
                    return e
            return None
        ifd0 = tiff + u32(tiff+4)
        sub = find(ifd0, 0x8769)
        if sub:
            subifd = tiff + u32(sub+8)
            e = find(subifd, 0x9003)
            if e:
                v = data[tiff+u32(e+8):tiff+u32(e+8)+19].decode('ascii','ignore')
                m = re.match(r'(\d{4}):(\d{2}):', v)
                if m and m.group(1) != '0000':
                    return (m.group(1), m.group(2))
    except Exception:
        pass
    return None

_dc = {}
def sidecar_has(path):
    d = os.path.dirname(path)
    if d not in _dc:
        m = set()
        try:
            for fn in os.listdir(d):
                if fn.endswith('.json') and 'metadata' in fn and fn != 'metadata.json':
                    try:
                        t = json.load(open(os.path.join(d, fn))).get('title')
                        if t:
                            m.add(t.lower())
                    except Exception:
                        pass
        except Exception:
            pass
        _dc[d] = m
    return os.path.basename(path).lower() in _dc[d]

heic = exi = sid = none = 0
none_dirs = {}
for root, _, files in os.walk(SRC):
    for fn in files:
        if not fn.lower().endswith('.heic'):
            continue
        heic += 1
        p = os.path.join(root, fn)
        if exif_ym(p):
            exi += 1
        elif sidecar_has(p):
            sid += 1
        else:
            none += 1
            k = root.split('/')[-1]
            none_dirs[k] = none_dirs.get(k, 0) + 1
print("HEIC total:", heic, "| has EXIF date:", exi, "| has sidecar:", sid, "| neither:", none)
print("'neither' by folder:", dict(sorted(none_dirs.items(), key=lambda x: -x[1])[:8]))
