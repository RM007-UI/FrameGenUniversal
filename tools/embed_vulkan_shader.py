#!/usr/bin/env python3
from pathlib import Path
import struct,sys
if len(sys.argv)!=3: raise SystemExit("usage: embed_vulkan_shader.py input.spv output.hpp")
data=Path(sys.argv[1]).read_bytes()
if len(data)%4 or data[:4]!=b"\x03\x02\x23\x07": raise SystemExit("invalid SPIR-V module")
words=struct.unpack("<"+"I"*(len(data)//4),data)
body=",\n".join("    "+",".join("0x%08xu"%x for x in words[i:i+8]) for i in range(0,len(words),8))
out="#pragma once\n#include <cstdint>\n#include <cstddef>\nnamespace framegen { inline constexpr uint32_t MOTION_MIDPOINT[] = {\n"+body+"\n}; inline constexpr size_t MOTION_MIDPOINT_WORDS=sizeof(MOTION_MIDPOINT)/sizeof(uint32_t); }\n"
Path(sys.argv[2]).write_text(out)
