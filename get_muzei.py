import urllib.request, re, sys, os

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
    'Accept': 'text/html,application/xhtml+xml',
    'Accept-Language': 'en-US,en;q=0.5',
    'Referer': 'https://www.apkmirror.com/',
}

page_url = 'https://www.apkmirror.com/apk/roman-nurik-and-ian-lake/muzei-live-wallpaper/muzei-live-wallpaper-3-6-2-release/muzei-live-wallpaper-3-6-2-android-apk-download/'

req = urllib.request.Request(page_url, headers=headers)
with urllib.request.urlopen(req) as r:
    html = r.read().decode('utf-8', errors='ignore')

match = re.search(r'href="(/wp-content/themes/APKMirror/download\.php\?[^"]+)"', html)
if match:
    dl_path = match.group(1).replace('&amp;', '&')
    dl_url = 'https://www.apkmirror.com' + dl_path
    print('Download URL:', dl_url)
    out = os.path.join(os.environ['TEMP'], 'muzei.apk')
    req2 = urllib.request.Request(dl_url, headers=headers)
    with urllib.request.urlopen(req2) as r2, open(out, 'wb') as f:
        f.write(r2.read())
    print('Saved to:', out)
else:
    # debug: show relevant snippet
    idx = html.find('APKMirror')
    print('Could not find download link. Snippet:', html[2000:2500])
