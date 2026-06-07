import os, struct, json, shutil, re, sys
from datetime import datetime

SRC = "/volume1/Media/Photos/GooglePhotos/Takeout/Google Photos"
DST = "/volume1/homes/welps/Photos"
IMG_EXT = {'.jpg', '.jpeg', '.png', '.heic', '.gif'}
DRY = (len(sys.argv) > 1 and sys.argv[1] == 'dry')

def _parse_tiff(data, tiff):
    # Parse a TIFF/EXIF blob starting at offset `tiff`; return (year,month) or None.
    bo = data[tiff:tiff+2]
    endian = '<' if bo == b'II' else '>' if bo == b'MM' else None
    if not endian:
        return None
    try:
        u16 = lambda o: struct.unpack(endian+'H', data[o:o+2])[0]
        u32 = lambda o: struct.unpack(endian+'I', data[o:o+4])[0]
        if u16(tiff+2) != 42:           # TIFF magic check
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
        # Try DateTimeOriginal (in Exif sub-IFD), then plain DateTime (IFD0).
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
    # Works for JPEG and HEIC: locate the EXIF TIFF header directly and parse it.
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

# Global filename -> (year, month) from EVERY sidecar in the tree.
# Lets an album-folder photo (no local sidecar) borrow the date from the
# matching sidecar that sits with the original in a year folder.
GLOBAL = {}
def build_global():
    for root, _, files in os.walk(SRC):
        for fn in files:
            if fn.endswith('.json') and 'metadata' in fn and fn != 'metadata.json':
                try:
                    j = json.load(open(os.path.join(root, fn)))
                    t = j.get('title')
                    ts = j.get('photoTakenTime', {}).get('timestamp')
                    if t and ts:
                        dt = datetime.utcfromtimestamp(int(ts))
                        GLOBAL.setdefault(t.lower(), (f"{dt.year:04d}", f"{dt.month:02d}"))
                except Exception:
                    pass

def folder_ym(path):
    m = re.match(r'(\d{4})-(\d{2})-\d{2}', os.path.basename(os.path.dirname(path)))
    return (m.group(1), m.group(2)) if m else None

build_global()
print("global sidecar entries:", len(GLOBAL), flush=True)

src_counts = {'exif': 0, 'sidecar': 0, 'folder': 0, 'undated': 0}
undated_ext = {}
years = {}
moved = 0
for root, _, files in os.walk(SRC):
    for fn in files:
        if os.path.splitext(fn)[1].lower() not in IMG_EXT:
            continue
        p = os.path.join(root, fn)
        ym = exif_ym(p)
        if ym:
            src_counts['exif'] += 1
        else:
            ym = GLOBAL.get(fn.lower())
            if ym:
                src_counts['sidecar'] += 1
            else:
                ym = folder_ym(p)
                if ym:
                    src_counts['folder'] += 1
        if ym:
            y, mo = ym
            years[y] = years.get(y, 0) + 1
            dest_dir = os.path.join(DST, y, mo)
        else:
            src_counts['undated'] += 1
            ex = os.path.splitext(fn)[1].lower()
            undated_ext[ex] = undated_ext.get(ex, 0) + 1
            dest_dir = os.path.join(DST, "Undated")
        if not DRY:
            os.makedirs(dest_dir, exist_ok=True)
            dest = os.path.join(dest_dir, fn)
            if os.path.exists(dest):
                base, e = os.path.splitext(fn); i = 1
                while os.path.exists(os.path.join(dest_dir, f"{base}_{i}{e}")): i += 1
                dest = os.path.join(dest_dir, f"{base}_{i}{e}")
            shutil.move(p, dest)
        moved += 1
        if moved % 1000 == 0:
            print("  processed", moved, flush=True)

print("MODE:", "DRY-RUN (nothing moved)" if DRY else "MOVED")
print("total images:", moved)
print("date source:", src_counts)
print("undated by ext:", undated_ext)
print("by year:", dict(sorted(years.items())))
