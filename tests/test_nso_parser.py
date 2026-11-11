import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from nso import Nso


def put32(buf, off, value):
    struct.pack_into("<I", buf, off, value)


def put64(buf, off, value):
    struct.pack_into("<Q", buf, off, value)


def put_sym(buf, off, name_off, section):
    struct.pack_into("<IBBHQQ", buf, off, name_off, 0x12, 0, section, 0, 0)


def make_gnu_hash_nso():
    text = bytearray(0x100)
    rodata = bytearray(0x100)
    data = bytearray(0x200)
    put32(text, 4, 0x20)
    text[0x20:0x24] = b"MOD0"
    put32(text, 0x24, 0x40)

    strings = b"\0libnn_sdk.so\0_ZN2nn4init5StartEmmPFvvES2_\0nn::TestImport\0"
    needed = 1
    startup = strings.index(b"_ZN2nn4init")
    imported = strings.index(b"nn::TestImport")

    # GNU hash at image offset 0x110: two symbols after the null symbol.
    put32(rodata, 0x10, 1)       # nbuckets
    put32(rodata, 0x14, 1)       # symoffset
    put32(rodata, 0x18, 1)       # bloom_size
    put32(rodata, 0x1c, 0)       # bloom_shift
    put64(rodata, 0x20, 0)       # bloom word
    put32(rodata, 0x28, 1)       # bucket starts at symbol 1
    put32(rodata, 0x2c, 0)       # symbol 1 chain continues
    put32(rodata, 0x30, 1)       # symbol 2 chain terminates
    rodata[0x40:0x40 + len(strings)] = strings

    put_sym(rodata, 0x80 + 24, imported, 0)
    put_sym(rodata, 0x80 + 48, startup, 1)
    put64(data, 0x30, 0x50)
    put64(data, 0x38, 0x402)
    put64(data, 0x40, 0)
    put64(data, 0x80, 0x300)     # one RELR target inside this module image

    dynamic = [
        (1, needed),
        (0x6FFFFEF5, 0x110),      # DT_GNU_HASH, without DT_HASH
        (5, 0x140),
        (6, 0x180),
        (7, 0x230),
        (8, 24),
        (10, len(strings)),
        (36, 0x280),              # DT_RELR
        (35, 8),                  # DT_RELRSZ
        (0, 0),
    ]
    for i, (tag, value) in enumerate(dynamic):
        put64(text, 0x60 + i * 16, tag)
        put64(text, 0x68 + i * 16, value)

    raw = bytearray(0x500)
    raw[:4] = b"NSO0"
    put32(raw, 0x10, 0x100)
    put32(raw, 0x14, 0)
    put32(raw, 0x18, len(text))
    put32(raw, 0x20, 0x200)
    put32(raw, 0x24, 0x100)
    put32(raw, 0x28, len(rodata))
    put32(raw, 0x30, 0x300)
    put32(raw, 0x34, 0x200)
    put32(raw, 0x38, len(data))
    put32(raw, 0x60, len(text))
    put32(raw, 0x64, len(rodata))
    put32(raw, 0x68, len(data))
    raw[0x100:0x200] = text
    raw[0x200:0x300] = rodata
    raw[0x300:0x500] = data
    return raw


class NsoParserTests(unittest.TestCase):
    def test_reads_gnu_hash_without_sysv_hash_and_decodes_relr(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "main"
            path.write_bytes(make_gnu_hash_nso())
            module = Nso(str(path))

        self.assertEqual(module.symbol_hash_style, "GNU")
        self.assertIn("nn::TestImport", [s[0] for s in module.imports()])
        self.assertIn("_ZN2nn4init5StartEmmPFvvES2_", [s[0] for s in module.exports()])
        self.assertEqual(module.packed_relative_relocations, [0x300])


if __name__ == "__main__":
    unittest.main()
