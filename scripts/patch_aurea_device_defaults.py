#!/usr/bin/env python3
"""Apply conservative export defaults for the VILLAON V501L (2 GB RAM).

The stock export dialog clamps its initial short side to 720..1080 px.
On this low-memory GLES device, start at 480..720 px instead. The user can
still select a higher resolution manually in the export dialog.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_aurea_device_defaults.py PATH_TO_ER1_SMALI")

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
start = text.find(".method public static final f(")
if start < 0:
    raise SystemExit("Could not find Aurea export-dialog method er1.f")
end = text.find(".end method", start)
if end < 0:
    raise SystemExit("Could not find end of Aurea export-dialog method")
end += len(".end method")
method = text[start:end]

old_min = "const/16 v12, 0x2d0"
old_max = "const/16 v12, 0x438"
if method.count(old_min) != 1 or method.count(old_max) != 1:
    raise SystemExit("Unexpected export resolution clamp; refusing an unsafe patch")

method = method.replace(old_min, "const/16 v12, 0x1e0", 1)
method = method.replace(old_max, "const/16 v12, 0x2d0", 1)
updated = text[:start] + method + text[end:]
if "const/16 v12, 0x1e0" not in method or method.count("const/16 v12, 0x2d0") < 1:
    raise SystemExit("Export resolution patch validation failed")

path.write_text(updated, encoding="utf-8")
print("Patched Aurea export default clamp from 720–1080 px to 480–720 px.")
