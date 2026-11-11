import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "clustertruck_probe.py"
SPEC = importlib.util.spec_from_file_location("clustertruck_probe", MODULE_PATH)
probe = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(probe)


class ClustertruckProbeTests(unittest.TestCase):
    def test_classifies_nso(self):
        self.assertEqual(probe.classify_magic(b"NSO0" + b"\0" * 512), "Nintendo Switch NSO executable")

    def test_classifies_pfs0(self):
        self.assertEqual(probe.classify_magic(b"PFS0" + b"\0" * 512), "PFS0 package container (commonly NSP)")

    def test_classifies_xci_header(self):
        sample = bytearray(0x204)
        sample[0x100:0x104] = b"HEAD"
        self.assertEqual(probe.classify_magic(bytes(sample)), "XCI cartridge image header")

    def test_classifies_elf_magic(self):
        self.assertEqual(probe.classify_magic(b"\x7fELF" + b"\0" * 512), "ELF executable")

    def test_parses_pfs0_directory_without_extracting_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            package = Path(temp) / "clustertruck.nsp"
            payload = b"NCA3" + b"\0" * 700
            names = b"program.nca\0"
            header = b"PFS0" + struct.pack("<III", 1, len(names), 0)
            entry = struct.pack("<QQII", 0, len(payload), 0, 0)
            package.write_bytes(header + entry + names + payload)
            report = probe.parse_pfs0(package)
        self.assertEqual(report["entry_count"], 1)
        self.assertFalse(report["payloads_extracted"])
        self.assertEqual(report["entries"][0]["name"], "program.nca")
        self.assertEqual(report["entries"][0]["size_bytes"], len(payload))
        self.assertEqual(report["entries"][0]["signature"], "NCA content archive (may be encrypted)")

    def test_scan_directory_reports_switch_exefs_and_npdm_only(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            exefs = root / "exefs"
            exefs.mkdir()
            (exefs / "main").write_bytes(b"NSO0" + b"\0" * 512)
            (exefs / "sdk").write_bytes(b"NSO0" + b"\0" * 512)
            (exefs / "main.npdm").write_bytes(b"META" + b"\0" * 128)
            report = probe.scan_directory(root, 100, 8)
        self.assertEqual(report["extracted_nso_count"], 2)
        self.assertEqual(report["main_nso"]["path"], "exefs/main")
        self.assertEqual(report["main_npdm"]["path"], "exefs/main.npdm")
        self.assertIn("exefs/sdk", report["candidate_paths"])

    def test_scan_directory_summarizes_romfs_without_unpacking_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "exefs").mkdir()
            romfs = root / "romfs"
            (romfs / "StreamingAssets").mkdir(parents=True)
            (romfs / "StreamingAssets" / "level.bundle").write_bytes(b"UnityFS" + bytes(25))
            (romfs / "music.ogg").write_bytes(b"OggS" + bytes(12))
            report = probe.scan_directory(root, 100, 8)

        self.assertTrue(report["exefs_found"])
        self.assertTrue(report["romfs"]["found"])
        self.assertEqual(report["romfs"]["file_count"], 2)
        self.assertEqual(report["romfs"]["total_bytes"], 48)
        self.assertEqual(report["romfs"]["extension_counts"], {".bundle": 1, ".ogg": 1})
        self.assertEqual(report["romfs"]["recognized_signatures"], {"OggS": 1, "UnityFS": 1})
        self.assertIn("romfs/StreamingAssets/level.bundle", report["romfs"]["sample_paths"])

    def test_package_file_is_not_extracted(self):
        with tempfile.TemporaryDirectory() as temp:
            package = Path(temp) / "clustertruck.nsp"
            package.write_bytes(b"PFS0" + b"\0" * 1024)
            result = probe.inspect_single_file(package)
        self.assertEqual(result["input_kind"], "PFS0 package container (commonly NSP)")
        self.assertFalse(result["package_unpacking_performed"])
        self.assertIn("not extracted or decrypted", result["message"])


if __name__ == "__main__":
    unittest.main()
