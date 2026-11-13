#!/usr/bin/env python3
"""Explicitly register Aurea's bundled fallback fonts after native engine init.

The app copies these assets into cache/motor before nativeInitialize(). On some
Android builds the native FontManager can miss fallback files during its initial
system-font scan. Calling the same nativeImportFont API used by the app's
user-imported fonts makes the bundled fallbacks visible to the engine.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_aurea_font_fallback.py PATH_TO_C01_SMALI")

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
anchor = "    invoke-virtual {v5}, Lsf1;->m4()V"
if text.count(anchor) != 1:
    raise SystemExit("Could not uniquely locate successful native-engine initialization callback")

insert = """    # Explicitly register bundled fonts so text/glyph rendering has a known fallback.
    new-instance v1, Ljava/io/File;
    const-string v2, "Roboto-Regular.ttf"
    invoke-direct {v1, p0, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;
    move-result-object v2
    iget-object v1, v5, Lsf1;->d:Lcom/aurea/aurea/engine/AureaEngine;
    invoke-static {v1, v2}, Lcom/aurea/aurea/engine/AureaEngine;->n(Lcom/aurea/aurea/engine/AureaEngine;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1

    new-instance v1, Ljava/io/File;
    const-string v2, "NotoNaskhArabic-Regular.ttf"
    invoke-direct {v1, p0, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;
    move-result-object v2
    iget-object v1, v5, Lsf1;->d:Lcom/aurea/aurea/engine/AureaEngine;
    invoke-static {v1, v2}, Lcom/aurea/aurea/engine/AureaEngine;->n(Lcom/aurea/aurea/engine/AureaEngine;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1

    new-instance v1, Ljava/io/File;
    const-string v2, "NotoSansJP-Regular.otf"
    invoke-direct {v1, p0, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;
    move-result-object v2
    iget-object v1, v5, Lsf1;->d:Lcom/aurea/aurea/engine/AureaEngine;
    invoke-static {v1, v2}, Lcom/aurea/aurea/engine/AureaEngine;->n(Lcom/aurea/aurea/engine/AureaEngine;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1

"""
updated=text.replace(anchor,anchor+"\n"+insert.rstrip("\n"),1)
if updated.count("NotoSansJP-Regular.otf") != 1 or updated.count("Roboto-Regular.ttf") != 1:
    raise SystemExit("Font fallback registration patch validation failed")
path.write_text(updated,encoding="utf-8")
print("Added explicit native registration for Roboto, Noto Naskh Arabic, and Noto Sans JP.")
