import os
import zipfile

ROOT = r"C:/Users/i/WorkBuddy/2026-10-17-11-50-33/BarcodeApp/plugins"
OUT = r"C:/Users/i/WorkBuddy/2026-10-17-11-50-33/BarcodeApp/plugins_dist"
os.makedirs(OUT, exist_ok=True)

for name in sorted(os.listdir(ROOT)):
    d = os.path.join(ROOT, name)
    if not os.path.isdir(d):
        continue
    if not os.path.exists(os.path.join(d, "plugin.bsmanifest.json")):
        print("skip (no manifest):", name)
        continue
    zpath = os.path.join(OUT, name + ".bsplugin")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(os.listdir(d)):
            z.write(os.path.join(d, f), arcname=f)
    print("pack ->", os.path.basename(zpath))
