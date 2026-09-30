"""Prepare (but never flash) USB preset B from this board's own backups.

The current RK3399 image stores its DTB in the RSCE resource partition and
its initramfs as a gzip-compressed newc archive behind a KRNL header in boot.
Preset A is the untouched original image. Preset B changes only the two DWC3
dr_mode properties and the equal-length USB gadget controller string.
"""

import argparse
import gzip
import hashlib
import json
import struct
from pathlib import Path


BLOCK = 512
TYPE_C = "/usb@fe800000/dwc3@fe800000"
TYPE_A = "/usb@fe900000/dwc3@fe900000"


def align4(value):
    return (value + 3) & ~3


def patch_dtb(blob):
    if len(blob) < 40 or struct.unpack_from(">I", blob)[0] != 0xD00DFEED:
        raise ValueError("Not a flattened device tree")
    total, struct_off, strings_off = struct.unpack_from(">III", blob, 4)
    strings_len, struct_len = struct.unpack_from(">II", blob, 32)
    if total > len(blob) or struct_off + struct_len > total or strings_off + strings_len > total:
        raise ValueError("Invalid DTB bounds")
    source = blob[struct_off : struct_off + struct_len]
    strings = blob[strings_off : strings_off + strings_len]
    output = bytearray()
    stack = []
    seen = set()
    pos = 0
    while pos < len(source):
        begin = pos
        token = struct.unpack_from(">I", source, pos)[0]
        pos += 4
        if token == 1:  # FDT_BEGIN_NODE
            end = source.index(0, pos)
            stack.append(source[pos:end].decode("ascii"))
            pos = align4(end + 1)
            output.extend(source[begin:pos])
        elif token == 2:  # FDT_END_NODE
            stack.pop()
            output.extend(source[begin:pos])
        elif token == 3:  # FDT_PROP
            size, name_off = struct.unpack_from(">II", source, pos)
            pos += 8
            value = source[pos : pos + size]
            pos = align4(pos + size)
            name_end = strings.index(0, name_off)
            name = strings[name_off:name_end]
            path = "/" + "/".join(part for part in stack if part)
            if name == b"dr_mode" and path in (TYPE_C, TYPE_A):
                expected, replacement = (
                    (b"host\0", b"otg\0") if path == TYPE_C else (b"otg\0", b"host\0")
                )
                if value != expected:
                    raise ValueError(f"Unexpected {path} dr_mode: {value!r}")
                seen.add(path)
                output.extend(struct.pack(">III", 3, len(replacement), name_off))
                output.extend(replacement)
                output.extend(bytes(align4(len(replacement)) - len(replacement)))
            else:
                output.extend(source[begin:pos])
        elif token in (4, 9):  # FDT_NOP / FDT_END
            output.extend(source[begin:pos])
            if token == 9:
                output.extend(source[pos:])
                break
        else:
            raise ValueError(f"Unknown FDT token {token} at {begin}")
    if seen != {TYPE_C, TYPE_A} or len(output) != struct_len:
        raise ValueError("DTB layout/USB nodes differ from the validated board")
    patched = bytearray(blob)
    patched[struct_off : struct_off + struct_len] = output
    return bytes(patched)


def patch_resource(image):
    if len(image) != 16 * 1024 * 1024 or image[:4] != b"RSCE":
        raise ValueError("Unexpected resource partition")
    count = struct.unpack_from("<I", image, 12)[0]
    if image[8:11] != b"\1\1\1" or count != 2:
        raise ValueError("Unexpected RSCE index layout")
    result = bytearray(image)
    found = False
    for index in range(count):
        entry = image[BLOCK * (index + 1) : BLOCK * (index + 2)]
        if entry[:4] != b"ENTR":
            raise ValueError("Invalid RSCE entry")
        name = entry[4:224].split(b"\0", 1)[0]
        if name == b"rk-kernel.dtb":
            hash_size, block_offset, size = struct.unpack_from("<III", entry, 256)
            if hash_size != 0:
                raise ValueError("Hashed DTB needs a supported hash update")
            offset = block_offset * BLOCK
            if offset + size > len(image):
                raise ValueError("DTB outside resource partition")
            replacement = patch_dtb(image[offset : offset + size])
            if len(replacement) != size:
                raise ValueError("DTB size changed")
            result[offset : offset + size] = replacement
            found = True
    if not found:
        raise ValueError("rk-kernel.dtb not found")
    return bytes(result)


def patch_boot(image):
    if len(image) != 32 * 1024 * 1024 or image[:4] != b"KRNL":
        raise ValueError("Unexpected boot partition")
    compressed_len = struct.unpack_from("<I", image, 4)[0]
    if compressed_len <= 0 or compressed_len > len(image) - 8:
        raise ValueError("Invalid KRNL length")
    archive = gzip.decompress(image[8 : 8 + compressed_len])
    if not archive.startswith(b"070701") or archive.count(b"fe900000.dwc3") != 1:
        raise ValueError("Unexpected initramfs / USB controller occurrence")
    patched_archive = archive.replace(b"fe900000.dwc3", b"fe800000.dwc3")
    compressed = gzip.compress(patched_archive, mtime=0)
    if len(compressed) > len(image) - 8:
        raise ValueError("Modified initramfs exceeds boot partition")
    result = bytearray(image)
    result[4:8] = struct.pack("<I", len(compressed))
    result[8 : 8 + max(compressed_len, len(compressed))] = bytes(max(compressed_len, len(compressed)))
    result[8 : 8 + len(compressed)] = compressed
    if gzip.decompress(result[8 : 8 + len(compressed)]) != patched_archive:
        raise ValueError("Boot image verification failed")
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resource", required=True, type=Path)
    parser.add_argument("--boot", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    resource = args.resource.read_bytes()
    boot = args.boot.read_bytes()
    resource_b = patch_resource(resource)
    boot_b = patch_boot(boot)
    if resource_b == resource or boot_b == boot:
        raise ValueError("Preset B is identical to preset A")
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in (("resource-mode-b.img", resource_b), ("boot-mode-b.img", boot_b)):
        path = args.output / name
        if path.exists():
            raise FileExistsError(path)
        path.write_bytes(data)
    manifest = {
        name: hashlib.sha256(data).hexdigest()
        for name, data in (("resource-mode-a.img", resource), ("boot-mode-a.img", boot),
                           ("resource-mode-b.img", resource_b), ("boot-mode-b.img", boot_b))
    }
    (args.output / "SHA256.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
