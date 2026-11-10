"""Smoke-test the generic AArch64 NSO code-generation path without a game dump.

The fixture is a tiny synthetic NSO with one RET instruction and an arbitrary build ID.
It proves the generator can get past the Zelda-only build-ID gate and emit code without
applying any title-specific patch tables. It does not model Clustertruck or its runtime.
"""
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GEN = ROOT / "tools" / "gen.py"
BUILD_ID = bytes(range(1, 33))


def make_synthetic_nso(path: Path) -> None:
    text = bytearray(0x204)
    rodata = bytearray(0x200)
    data = bytearray(0x10)

    # NSO MOD0 pointer and the six signed offsets in the MOD0 header.
    struct.pack_into("<I", text, 4, 0x80)
    text[0x80:0x84] = b"MOD0"
    struct.pack_into("<6i", text, 0x84, 0x20, 0x2080, 0x2080, -0x80, -0x80, -0x80)

    # Minimal ELF dynamic table: SysV hash, string table, symbol table, then DT_NULL.
    struct.pack_into("<QQQQQQQQ", text, 0xA0, 4, 0x1000, 5, 0x1020, 6, 0x1040, 0, 0)
    struct.pack_into("<II", rodata, 0, 1, 2)  # one bucket, two symbols
    rodata[0x20:0x2B] = b"\0fake_main\0"
    struct.pack_into("<IBBHQQ", rodata, 0x40 + 24, 1, 0x12, 0, 1, 0x200, 4)

    # One supported AArch64 instruction: RET.
    struct.pack_into("<I", text, 0x200, 0xD65F03C0)

    header = bytearray(0x100)
    header[:4] = b"NSO0"
    header[0x40:0x60] = BUILD_ID
    text_file = len(header)
    ro_file = text_file + len(text)
    data_file = ro_file + len(rodata)
    segments = [
        (text_file, 0x0000, len(text)),
        (ro_file, 0x1000, len(rodata)),
        (data_file, 0x2000, len(data)),
    ]
    for i, (file_off, mem_off, size) in enumerate(segments):
        struct.pack_into("<III", header, 0x10 + i * 0x10, file_off, mem_off, size)
        struct.pack_into("<I", header, 0x60 + i * 4, size)

    path.write_bytes(header + text + rodata + data)


class GenericNsoRecompTests(unittest.TestCase):
    def test_android_import_has_exefs_only_recompile_path(self):
        launcher = (ROOT / "runtime" / "ui" / "launcher.mm").read_text()
        activity = (ROOT / "android" / "lasrecomp" / "app" / "src" / "main" / "java" /
                    "com" / "lasrecomp" / "LASRecompActivity.java").read_text()
        self.assertIn("g_android_recompile_only.exchange(false)", launcher)
        self.assertIn("Generic recompilation finished", launcher)
        self.assertIn("boolean compileOnly = romfs == null && romfsBin == null;", activity)
        self.assertIn("nativeImportDone(6)", activity)
        self.assertIn('named(kids, "sdk") != null', activity)

    def test_android_gamebuild_uses_generic_mode_for_main_only(self):
        source = (ROOT / "runtime" / "gamebuild.c").read_text()
        self.assertIn('if (i == 0) add(&a, "--generic");', source)
        self.assertIn('if (i == 1) { add(&a, "--hle"); add(&a, t.hle); }', source)
        self.assertIn('unprofiled title: Zelda hot-function hints disabled', source)
        script = (ROOT / "android" / "build_gamelib.sh").read_text()
        self.assertIn('gen.py main "$EXEFS/main" 0x7000000000 "$GEN/main" --generic --simd asm --dyn-base', script)
        self.assertNotIn('gen.py sdk "$EXEFS/sdk" 0x7004000000 "$GEN/sdk" --generic', script)

    def run_generator(self, nso: Path, out: Path, generic: bool) -> subprocess.CompletedProcess:
        command = [
            sys.executable, str(GEN), "main", str(nso), "0x7000000000", str(out),
            "--simd", "c", "--dyn-base",
        ]
        if generic:
            command.append("--generic")
        return subprocess.run(command, cwd=ROOT / "tools", text=True, capture_output=True, timeout=90)

    def test_unknown_build_id_is_rejected_by_zeldaprofile_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nso, out = root / "main", root / "normal"
            make_synthetic_nso(nso)
            result = self.run_generator(nso, out, generic=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unsupported version of the game", result.stderr)
            self.assertFalse((out / "main_table.c").exists())

    def test_generic_mode_emits_code_for_unknown_build_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nso, out = root / "main", root / "generic"
            make_synthetic_nso(nso)
            result = self.run_generator(nso, out, generic=True)
            self.assertEqual(result.returncode, 0, msg=result.stderr[-4000:])
            self.assertEqual((out / "main_build_id.txt").read_text().strip(), BUILD_ID.hex())
            table = (out / "main_table.c").read_text()
            self.assertIn("main_fn_count = 1", table)
            self.assertIn("lasgame_set_guest_base", table)
            generated = (out / "main_000.c").read_text()
            self.assertIn("void f_7000000200(Cpu *c)", generated)
            self.assertIn("return;", generated)
            self.assertNotIn("rt_aspect_site", generated)


if __name__ == "__main__":
    unittest.main()
