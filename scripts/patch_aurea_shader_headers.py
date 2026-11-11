#!/usr/bin/env python3
"""Move Aurea's GLSL #version directive ahead of its SPIR-V hash comment.

The original binary embeds shader sources as:
    // AUREA_SPIRV_FNV64 <hash>
    #version 310 es

Some GLES drivers reject this because #version is not on line 1. The
transformation swaps those two lines without changing the byte count or
altering the hash comment.
"""
from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_aurea_shader_headers.py PATH_TO_LIBAUREA_SO")

path = Path(sys.argv[1])
data = path.read_bytes()

pattern = re.compile(
    rb"(// AUREA_SPIRV_FNV64 [0-9a-f]{1,16})\n(#version [^\r\n]+)\n"
)

patched, count = pattern.subn(
    lambda match: match.group(2) + b"\n" + match.group(1) + b"\n",
    data,
)

if count != 290:
    raise SystemExit(
        f"Refusing to patch unexpected library: found {count} shader headers; expected 290"
    )

if len(patched) != len(data):
    raise SystemExit("Patch unexpectedly changed the native library size")

if re.search(rb"// AUREA_SPIRV_FNV64 [0-9a-f]{1,16}\n#version ", patched):
    raise SystemExit("A shader still has #version on line 2")

if len(re.findall(rb"#version [^\r\n]+\n// AUREA_SPIRV_FNV64 ", patched)) != count:
    raise SystemExit("Post-patch validation failed")

path.write_bytes(patched)
print(f"Patched {count} shader headers; library size remains {len(patched)} bytes.")
