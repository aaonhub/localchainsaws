#!/usr/bin/env python3
"""Writes the empty gametest structure (21x40x21 of nothing) as a gzipped NBT structure file."""
import gzip, struct, sys
from pathlib import Path

def s(b: str):
    raw = b.encode()
    return struct.pack(">H", len(raw)) + raw

def named(tag_id, name, payload):
    return bytes([tag_id]) + s(name) + payload

TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_END = 3, 8, 9, 10, 0

def int_list(vals):
    return bytes([TAG_INT]) + struct.pack(">i", len(vals)) + b"".join(struct.pack(">i", v) for v in vals)

air = named(TAG_STRING, "Name", s("minecraft:air")) + bytes([TAG_END])
root = (
    named(TAG_INT, "DataVersion", struct.pack(">i", 3465))
    + named(TAG_LIST, "size", int_list([21, 40, 21]))
    + named(TAG_LIST, "palette", bytes([TAG_COMPOUND]) + struct.pack(">i", 1) + air)
    + named(TAG_LIST, "blocks", bytes([TAG_END]) + struct.pack(">i", 0))
    + named(TAG_LIST, "entities", bytes([TAG_END]) + struct.pack(">i", 0))
    + bytes([TAG_END])
)
out = Path(__file__).resolve().parent.parent / "src/main/resources/data/localchainsaws/structures/arena.nbt"
out.write_bytes(gzip.compress(named(TAG_COMPOUND, "", root), mtime=0))
print(out)
