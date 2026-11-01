#!/usr/bin/env python3
# 手写最小 resources.arsc：只含一个 string-array 资源，供 xposedscope 推荐作用域使用。
import struct

PKG_ID = 0x7f
TYPE_ID = 1          # array
ENTRY_ID = 0         # scope
RES_ID = (PKG_ID << 24) | (TYPE_ID << 16) | ENTRY_ID   # 0x7f010000

RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_SPEC_TYPE = 0x0202
RES_TABLE_TYPE_TYPE = 0x0201

FLAG_COMPLEX = 0x0001
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03


def encode_str(s):
    b = s.encode("utf-8")
    out = b""
    n16 = len(s)
    if n16 > 0x7F:
        out += bytes([(n16 >> 8) | 0x80, n16 & 0xFF])
    else:
        out += bytes([n16])
    if len(b) > 0x7F:
        out += bytes([(len(b) >> 8) | 0x80, len(b) & 0xFF])
    else:
        out += bytes([len(b)])
    return out + b + b"\x00"


def string_pool(strings):
    offsets, data = [], b""
    for s in strings:
        offsets.append(len(data))
        data += encode_str(s)
    while len(data) % 4:
        data += b"\x00"
    count = len(strings)
    header_size = 28
    strings_start = header_size + 4 * count
    out = struct.pack("<HHI", RES_STRING_POOL_TYPE, header_size, strings_start + len(data))
    out += struct.pack("<IIIII", count, 0, 0x100, strings_start, 0)
    for off in offsets:
        out += struct.pack("<I", off)
    return out + data


def type_spec(type_id, entry_count):
    body = struct.pack("<HHI", RES_TABLE_TYPE_SPEC_TYPE, 16, 16 + 4 * entry_count)
    body += struct.pack("<BBHI", type_id, 0, 0, entry_count)
    for _ in range(entry_count):
        body += struct.pack("<I", 0)   # configChangeFlags
    return body


def type_chunk(type_id, entries):
    """entries: list of bytes (已编码的 entry，可为空表示无此 entry)"""
    count = len(entries)
    # ResTable_config 首字段是 size(uint32)，必须等于自身长度
    config = struct.pack("<I", 64) + b"\x00" * 60
    header_size = 8 + 1 + 1 + 2 + 4 + 4 + len(config)
    offsets = b""
    data = b""
    for e in entries:
        if e is None:
            offsets += struct.pack("<I", 0xFFFFFFFF)   # NO_ENTRY
        else:
            offsets += struct.pack("<I", len(data))
            data += e
    entries_start = header_size + len(offsets)
    total = entries_start + len(data)
    out = struct.pack("<HHI", RES_TABLE_TYPE_TYPE, header_size, total)
    out += struct.pack("<BBHI", type_id, 0, 0, count)
    out += struct.pack("<I", entries_start)
    out += config
    out += offsets
    out += data
    return out


def complex_array_entry(key_index, items):
    """items: list of (name_id, value_type, value_data)"""
    out = struct.pack("<HHI", 16, FLAG_COMPLEX, key_index)
    out += struct.pack("<II", 0, len(items))
    for name_id, vtype, vdata in items:
        out += struct.pack("<I", name_id)
        out += struct.pack("<HBBI", 8, 0, vtype, vdata)
    return out


def build_arsc(package_name, scope_packages):
    value_pool = string_pool(scope_packages)
    type_pool = string_pool(["array"])
    key_pool = string_pool(["scope"])

    spec = type_spec(TYPE_ID, 1)
    # string-array 的每一项 name 是 0x01000000 | index
    items = [(0x01000000 | i, TYPE_STRING, i) for i in range(len(scope_packages))]
    entry = complex_array_entry(0, items)
    tchunk = type_chunk(TYPE_ID, [entry])

    pkg_body = type_pool + key_pool + spec + tchunk
    pkg_header_size = 288
    name_field = package_name.encode("utf-16-le") + b"\x00\x00"
    name_field = name_field.ljust(256, b"\x00")[:256]

    pkg = struct.pack("<HHI", RES_TABLE_PACKAGE_TYPE, pkg_header_size, pkg_header_size + len(pkg_body))
    pkg += struct.pack("<I", PKG_ID)
    pkg += name_field
    pkg += struct.pack("<I", pkg_header_size)                       # typeStrings
    pkg += struct.pack("<I", 1)                                     # lastPublicType
    pkg += struct.pack("<I", pkg_header_size + len(type_pool))      # keyStrings
    pkg += struct.pack("<I", 1)                                     # lastPublicKey
    pkg += struct.pack("<I", 0)                                     # typeIdOffset
    pkg += pkg_body

    body = value_pool + pkg
    header = struct.pack("<HHII", RES_TABLE_TYPE, 12, 12 + len(body), 1)
    return header + body


if __name__ == "__main__":
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else "resources.arsc"
    data = build_arsc("com.dsh.laydetectoff", ["com.oplus.gesture"])
    with open(out, "wb") as f:
        f.write(data)
    print("written %s %d bytes, array/scope -> 0x%08x" % (out, len(data), RES_ID))
