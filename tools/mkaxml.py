#!/usr/bin/env python3
# 手写 Android 二进制 XML (AXML) 生成器。
# 在没有 aapt2 的环境下生成可安装 APK 的 AndroidManifest.xml。
# 覆盖：manifest > application > meta-data（Xposed 模块识别必需）。
import struct

ATTR_VERSION_CODE = 0x0101021b
ATTR_VERSION_NAME = 0x0101021c
ATTR_LABEL = 0x01010001
ATTR_HAS_CODE = 0x0101000c
ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea
ATTR_NAME = 0x01010003
ATTR_VALUE = 0x01010024
ATTR_RESOURCE = 0x01010025
ATTR_MIN_SDK_VERSION = 0x0101020c
ATTR_TARGET_SDK_VERSION = 0x01010270

TYPE_REFERENCE = 0x01
SCOPE_ARRAY_RES_ID = 0x7f010000

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12
NO_INDEX = 0xFFFFFFFF
ANDROID_NS = "http://schemas.android.com/apk/res/android"


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


def make_string_pool(strings):
    offsets, data = [], b""
    for s in strings:
        offsets.append(len(data))
        data += encode_str(s)
    while len(data) % 4:
        data += b"\x00"
    count = len(strings)
    header_size = 28
    strings_start = header_size + 4 * count
    out = struct.pack("<HHI", 0x0001, header_size, strings_start + len(data))
    out += struct.pack("<IIIII", count, 0, 0x100, strings_start, 0)
    for off in offsets:
        out += struct.pack("<I", off)
    return out + data


def make_resource_map(res_ids):
    out = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(res_ids))
    for rid in res_ids:
        out += struct.pack("<I", rid)
    return out


def make_start_namespace(prefix_idx, uri_idx, line=1):
    body = struct.pack("<II", prefix_idx, uri_idx)
    return struct.pack("<HHIII", 0x0100, 16, 16 + len(body), line, NO_INDEX) + body


def make_end_namespace(prefix_idx, uri_idx, line=1):
    body = struct.pack("<II", prefix_idx, uri_idx)
    return struct.pack("<HHIII", 0x0101, 16, 16 + len(body), line, NO_INDEX) + body


def make_start_element(name_idx, attrs, ns_idx=NO_INDEX, line=1):
    attr_bytes = b""
    for a in attrs:
        attr_bytes += struct.pack("<III", a["ns"], a["name"], a["raw"])
        attr_bytes += struct.pack("<HBBI", 8, 0, a["type"], a["data"])
    ext = struct.pack("<IIHHHHHH", ns_idx, name_idx, 20, 20, len(attrs), 0, 0, 0)
    body = ext + attr_bytes
    return struct.pack("<HHIII", 0x0102, 16, 16 + len(body), line, NO_INDEX) + body


def make_end_element(name_idx, ns_idx=NO_INDEX, line=1):
    body = struct.pack("<II", ns_idx, name_idx)
    return struct.pack("<HHIII", 0x0103, 16, 16 + len(body), line, NO_INDEX) + body


def build_manifest(package, version_code, version_name, label, module_desc, min_version=82, min_sdk=29, target_sdk=35):
    attr_names = ["versionCode", "versionName", "label", "hasCode",
                  "extractNativeLibs", "name", "value", "resource",
                  "minSdkVersion", "targetSdkVersion"]
    res_ids = [ATTR_VERSION_CODE, ATTR_VERSION_NAME, ATTR_LABEL, ATTR_HAS_CODE,
               ATTR_EXTRACT_NATIVE_LIBS, ATTR_NAME, ATTR_VALUE, ATTR_RESOURCE,
               ATTR_MIN_SDK_VERSION, ATTR_TARGET_SDK_VERSION]
    body_strings = ["package", "manifest", "uses-sdk", "application", "meta-data",
                    "android", ANDROID_NS,
                    package, version_name, label,
                    "xposedmodule", "xposeddescription", "xposedminversion", "xposedscope",
                    "true", module_desc, str(min_version)]
    strings = attr_names + body_strings
    i = {}
    for n, s in enumerate(strings):
        i.setdefault(s, n)

    ns = i[ANDROID_NS]
    c = b""
    c += make_string_pool(strings)
    c += make_resource_map(res_ids)
    c += make_start_namespace(i["android"], ns)

    c += make_start_element(i["manifest"], [
        {"ns": NO_INDEX, "name": i["package"], "raw": i[package],
         "type": TYPE_STRING, "data": i[package]},
        {"ns": ns, "name": i["versionCode"], "raw": NO_INDEX,
         "type": TYPE_INT_DEC, "data": version_code},
        {"ns": ns, "name": i["versionName"], "raw": i[version_name],
         "type": TYPE_STRING, "data": i[version_name]},
    ])

    c += make_start_element(i["uses-sdk"], [
        {"ns": ns, "name": i["minSdkVersion"], "raw": NO_INDEX,
         "type": TYPE_INT_DEC, "data": min_sdk},
        {"ns": ns, "name": i["targetSdkVersion"], "raw": NO_INDEX,
         "type": TYPE_INT_DEC, "data": target_sdk},
    ])
    c += make_end_element(i["uses-sdk"])

    c += make_start_element(i["application"], [
        {"ns": ns, "name": i["label"], "raw": i[label],
         "type": TYPE_STRING, "data": i[label]},
        {"ns": ns, "name": i["hasCode"], "raw": NO_INDEX,
         "type": TYPE_INT_BOOLEAN, "data": 1},
        {"ns": ns, "name": i["extractNativeLibs"], "raw": NO_INDEX,
         "type": TYPE_INT_BOOLEAN, "data": 1},
    ])

    for k, v in [("xposedmodule", "true"),
                 ("xposeddescription", module_desc)]:
        is_module_flag = k == "xposedmodule"
        c += make_start_element(i["meta-data"], [
            {"ns": ns, "name": i["name"], "raw": i[k],
             "type": TYPE_STRING, "data": i[k]},
            {"ns": ns, "name": i["value"],
             "raw": NO_INDEX if is_module_flag else i[v],
             "type": TYPE_INT_BOOLEAN if is_module_flag else TYPE_STRING,
             "data": 1 if is_module_flag else i[v]},
        ])
        c += make_end_element(i["meta-data"])

    # xposedminversion 用整数类型，避免被当成带转义的字符串
    c += make_start_element(i["meta-data"], [
        {"ns": ns, "name": i["name"], "raw": i["xposedminversion"],
         "type": TYPE_STRING, "data": i["xposedminversion"]},
        {"ns": ns, "name": i["value"], "raw": NO_INDEX,
         "type": TYPE_INT_DEC, "data": min_version},
    ])
    c += make_end_element(i["meta-data"])

    # 推荐作用域：@array/scope -> 0x7f010000，LSPosed 安装后自动勾选
    c += make_start_element(i["meta-data"], [
        {"ns": ns, "name": i["name"], "raw": i["xposedscope"],
         "type": TYPE_STRING, "data": i["xposedscope"]},
        {"ns": ns, "name": i["resource"], "raw": NO_INDEX,
         "type": TYPE_REFERENCE, "data": SCOPE_ARRAY_RES_ID},
    ])
    c += make_end_element(i["meta-data"])

    c += make_end_element(i["application"])
    c += make_end_element(i["manifest"])
    c += make_end_namespace(i["android"], ns)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(c)) + c


if __name__ == "__main__":
    import sys
    out_path = sys.argv[1] if len(sys.argv) > 1 else "AndroidManifest.xml"
    data = build_manifest(
        package="com.dsh.laydetectoff",
        version_code=2,
        version_name="1.1.0",
        label="平放检测关闭",
        module_desc="移除面朝下状态对屏幕息屏超时的缩短",
    )
    with open(out_path, "wb") as f:
        f.write(data)
    print("written %s %d bytes" % (out_path, len(data)))
