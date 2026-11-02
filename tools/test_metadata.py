#!/usr/bin/env python3
"""Validate the handmade AXML and ARSC metadata contract used by the APK build."""
import struct
import sys

sys.path.insert(0, __import__("os").path.dirname(__file__))
from mkaxml import build_manifest, TYPE_INT_BOOLEAN, TYPE_INT_DEC, TYPE_REFERENCE, TYPE_STRING
from mkarsc import build_arsc, RES_ID, RES_STRING_POOL_TYPE

NO_INDEX = 0xFFFFFFFF


def string_pool(data, offset):
    kind, header_size, chunk_size = struct.unpack_from("<HHI", data, offset)
    if kind != RES_STRING_POOL_TYPE:
        raise AssertionError(f"Expected string pool at {offset:#x}, found {kind:#x}")
    count, _, _, strings_start, _ = struct.unpack_from("<IIIII", data, offset + 8)
    offsets = struct.unpack_from("<" + "I" * count, data, offset + header_size)
    base = offset + strings_start
    result = []
    for relative in offsets:
        i = base + relative
        n16 = data[i]
        if n16 & 0x80:
            n16 = ((n16 & 0x7F) << 8) | data[i + 1]
            i += 2
        else:
            i += 1
        n8 = data[i]
        if n8 & 0x80:
            n8 = ((n8 & 0x7F) << 8) | data[i + 1]
            i += 2
        else:
            i += 1
        result.append(data[i:i + n8].decode("utf-8"))
    return result, chunk_size


def parse_manifest(data):
    if struct.unpack_from("<H", data, 0)[0] != 0x0003:
        raise AssertionError("Not an Android binary XML document")
    strings, _ = string_pool(data, 8)
    elements = []
    cursor = 8
    while cursor < len(data):
        kind, header_size, size = struct.unpack_from("<HHI", data, cursor)
        if kind == 0x0102:
            _, element_name, attr_start, attr_size, attr_count, *_ = struct.unpack_from(
                "<IIHHHHHH", data, cursor + 16
            )
            attrs = {}
            attrs_offset = cursor + 16 + attr_start
            for i in range(attr_count):
                pos = attrs_offset + i * attr_size
                _, attr_name, raw = struct.unpack_from("<III", data, pos)
                value_type = data[pos + 15]
                value_data = struct.unpack_from("<I", data, pos + 16)[0]
                attrs[strings[attr_name]] = (raw, value_type, value_data)
            elements.append((strings[element_name], attrs))
        cursor += size
    if cursor != len(data):
        raise AssertionError("Malformed AXML chunk lengths")
    return strings, elements


def test_manifest():
    data = build_manifest(
        package="com.dsh.laydetectoff",
        version_code=2,
        version_name="1.1.0",
        label="平放检测关闭",
        module_desc="移除面朝下状态对屏幕息屏超时的缩短",
        min_sdk=29,
        target_sdk=35,
    )
    strings, elements = parse_manifest(data)
    manifest = next(attrs for name, attrs in elements if name == "manifest")
    assert strings[manifest["package"][0]] == "com.dsh.laydetectoff"
    assert manifest["versionCode"][2] == 2
    assert strings[manifest["versionName"][0]] == "1.1.0"

    sdk = next(attrs for name, attrs in elements if name == "uses-sdk")
    assert sdk["minSdkVersion"][1:] == (TYPE_INT_DEC, 29)
    assert sdk["targetSdkVersion"][1:] == (TYPE_INT_DEC, 35)

    metadata = {}
    for name, attrs in elements:
        if name != "meta-data":
            continue
        metadata[strings[attrs["name"][0]]] = attrs
    assert metadata["xposedmodule"]["value"][1:] == (TYPE_INT_BOOLEAN, 1)
    assert strings[metadata["xposeddescription"]["value"][0]] == "移除面朝下状态对屏幕息屏超时的缩短"
    assert metadata["xposedminversion"]["value"][1:] == (TYPE_INT_DEC, 82)
    assert metadata["xposedscope"]["resource"][1:] == (TYPE_REFERENCE, RES_ID)
    print("binary manifest: package/version/SDK/Xposed metadata PASS")
    return data


def test_arsc():
    data = build_arsc("com.dsh.laydetectoff", ["android"])
    if struct.unpack_from("<H", data, 0)[0] != 0x0002:
        raise AssertionError("Not a resource table")
    strings, global_pool_size = string_pool(data, 12)
    assert strings == ["android"]

    package_offset = 12 + global_pool_size
    package_type, package_header_size, package_size = struct.unpack_from("<HHI", data, package_offset)
    assert package_type == 0x0200
    assert package_size == len(data) - package_offset
    type_strings_offset = struct.unpack_from("<I", data, package_offset + 268)[0]
    key_strings_offset = struct.unpack_from("<I", data, package_offset + 276)[0]
    type_names, _ = string_pool(data, package_offset + type_strings_offset)
    key_names, _ = string_pool(data, package_offset + key_strings_offset)
    assert type_names == ["array"]
    assert key_names == ["scope"]

    type_spec_offset = package_offset + type_strings_offset + (28 + 4 * len(type_names) + 12)
    # The key pool starts directly after the type pool; type-spec follows key pool.
    _, type_pool_size = string_pool(data, package_offset + type_strings_offset)
    _, key_pool_size = string_pool(data, package_offset + key_strings_offset)
    type_spec_offset = package_offset + 288 + type_pool_size + key_pool_size
    spec_type, spec_header_size, spec_size = struct.unpack_from("<HHI", data, type_spec_offset)
    assert spec_type == 0x0202 and spec_size == 20

    type_chunk_offset = type_spec_offset + spec_size
    chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, type_chunk_offset)
    assert chunk_type == 0x0201
    type_id, _, _, entry_count = struct.unpack_from("<BBHI", data, type_chunk_offset + 8)
    entries_start = struct.unpack_from("<I", data, type_chunk_offset + 16)[0]
    assert type_id == 1 and entry_count == 1
    entry_relative = struct.unpack_from("<I", data, type_chunk_offset + header_size)[0]
    entry_offset = type_chunk_offset + entries_start + entry_relative
    entry_size, entry_flags, key_index = struct.unpack_from("<HHI", data, entry_offset)
    parent, item_count = struct.unpack_from("<II", data, entry_offset + 8)
    assert entry_size == 16 and entry_flags == 1 and key_index == 0
    assert key_names[key_index] == "scope"
    assert parent == 0 and item_count == 1
    map_name, = struct.unpack_from("<I", data, entry_offset + 16)
    value_size, _, value_type, value_data = struct.unpack_from("<HBBI", data, entry_offset + 20)
    assert map_name == 0x01000000
    assert value_size == 8 and value_type == TYPE_STRING
    assert strings[value_data] == "android"
    assert struct.unpack_from("<I", data, 4)[0] == len(data)
    print("binary resources.arsc: array/scope bag maps to android PASS")
    return data


if __name__ == "__main__":
    test_manifest()
    test_arsc()
