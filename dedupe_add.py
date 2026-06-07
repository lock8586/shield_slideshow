import os, struct, re, sys, hashlib, shutil

LIB = "/volume1/homes/welps/Photos"
SRC = "/volume1/Media/Photos/DStaging"
IMG_EXT = {'.jpg', '.jpeg', '.png', '.heic', '.gif'}
DRY = (len(sys.argv) > 1 and sys.argv[1] == 'dry')

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
        for ptr, datetag in ((0x8769, 0x9003), (None, 0x0132)):
            ifd = ifd0
            if ptr is not None:
                p = find(ifd0, ptr)
                if not p:
                    continue
                ifd = tiff + u32(p+8)
            e = find(ifd, datetag)
            if e:
                v = data[tiff+u32(e+8):tiff+u32(e+8)+19].decode('ascii', 'ignore')
                m = re.match(r'(\d{4}):(\d{2}):', v)
                if m and m.group(1) != '0000':
                    return (m.group(1), m.group(2))
    except Exception:
        return None
    return None

def exif_ym(path):
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

def phash(path):
    try:
        sz = os.path.getsize(path)
        with open(path, 'rb') as f:
            chunk = f.read(262144)
        return f"{sz}-{hashlib.md5(chunk).hexdigest()}"
    except Exception:
        return None

def folder_year(path):
    m = re.match(r'(\d{4})$', os.path.basename(os.path.dirname(path)))
    return m.group(1) if m else None

existing = set()
for root, _, files in os.walk(LIB):
    if '@eaDir' in root:
        continue
    for fn in files:
        if os.path.splitext(fn)[1].lower() in IMG_EXT:
            h = phash(os.path.join(root, fn))
            if h:
                existing.add(h)
print("existing library hashes:", len(existing), flush=True)

added = dupes = undated = 0
years = {}
for root, _, files in os.walk(SRC):
    if '@eaDir' in root:
        continue
    for fn in files:
        if os.path.splitext(fn)[1].lower() not in IMG_EXT:
            continue
        p = os.path.join(root, fn)
        h = phash(p)
        if h and h in existing:
            dupes += 1
            continue
        ym = exif_ym(p)
        if ym:
            y, mo = ym; dest_dir = os.path.join(LIB, y, mo)
        else:
            fy = folder_year(p)
            if fy:
                y = fy; dest_dir = os.path.join(LIB, fy)
            else:
                y = None; undated += 1; dest_dir = os.path.join(LIB, "Undated")
        if y:
            years[y] = years.get(y, 0) + 1
        if not DRY:
            os.makedirs(dest_dir, exist_ok=True)
            dest = os.path.join(dest_dir, fn)
            if os.path.exists(dest):
                base, e = os.path.splitext(fn); i = 1
                while os.path.exists(os.path.join(dest_dir, f"{base}_{i}{e}")): i += 1
                dest = os.path.join(dest_dir, f"{base}_{i}{e}")
            shutil.move(p, dest)
        if h:
            existing.add(h)
        added += 1

print("MODE:", "DRY-RUN" if DRY else "MOVED")
print("D: images scanned:", added + dupes)
print("already in library (skipped):", dupes)
print("genuinely new (added):", added, "| undated:", undated)
print("new by year:", dict(sorted(years.items())))
