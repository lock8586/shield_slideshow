import os, json
SRC = "/volume1/Media/Photos/GooglePhotos/Takeout/Google Photos"
heics = []
for root, _, files in os.walk(SRC):
    for fn in files:
        if fn.lower().endswith('.heic'):
            heics.append(os.path.join(root, fn))
    if len(heics) >= 3:
        break
for p in heics[:3]:
    d = open(p, 'rb').read(3000000)
    dd = os.path.dirname(p)
    print("FILE:", os.path.basename(p), "| dir:", dd.split('/')[-1], "| size:", os.path.getsize(p))
    print("  'Exif\\0\\0' idx:", d.find(b'Exif\x00\x00'), "| 'Exif' idx:", d.find(b'Exif'))
    print("  TIFF II* idx:", d.find(b'II*\x00'), "| MM idx:", d.find(b'MM\x00*'))
    js = [f for f in os.listdir(dd) if f.endswith('.json') and 'metadata' in f and f != 'metadata.json']
    base = os.path.basename(p).lower()
    match = None
    for j in js:
        try:
            jj = json.load(open(os.path.join(dd, j)))
            if jj.get('title', '').lower() == base:
                match = jj.get('photoTakenTime', {}).get('timestamp'); break
        except Exception:
            pass
    print("  jsons in folder:", len(js), "| sidecar title-match ts:", match)
    # show a couple of json titles in this folder for comparison
    sample_titles = []
    for j in js[:4]:
        try:
            sample_titles.append(json.load(open(os.path.join(dd, j))).get('title'))
        except Exception:
            pass
    print("  sample json titles:", sample_titles)
