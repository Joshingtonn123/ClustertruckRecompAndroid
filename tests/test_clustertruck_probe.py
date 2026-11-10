import importlib.util
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
        import struct
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

    def test_reads_il2cpp_metadata_header(self):
        import struct
        with tempfile.TemporaryDirectory() as temp:
            metadata = Path(temp) / "global-metadata.dat"
            metadata.write_bytes(struct.pack("<II", 0xFAB11BAF, 24) + b"\0" * (2 * 1024 * 1024))
            report = probe.inspect_il2cpp_metadata(metadata)
        self.assertTrue(report["valid_magic"])
        self.assertEqual(report["metadata_version"], 24)

    def test_rejects_invalid_il2cpp_metadata_header(self):
        import struct
        with tempfile.TemporaryDirectory() as temp:
            metadata = Path(temp) / "global-metadata.dat"
            metadata.write_bytes(struct.pack("<II", 0x12345678, 24))
            report = probe.inspect_il2cpp_metadata(metadata)
        self.assertFalse(report["valid_magic"])
        self.assertNotIn("metadata_version", report)

    def test_scan_directory_reads_large_metadata_header_and_unity_version(self):
        import struct
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            metadata = root / "romfs" / "Data" / "Managed" / "Metadata" / "global-metadata.dat"
            metadata.parent.mkdir(parents=True)
            metadata.write_bytes(struct.pack("<II", 0xFAB11BAF, 24) + b"\0" * (2 * 1024 * 1024))
            managers = root / "romfs" / "Data" / "globalgamemanagers"
            managers.parent.mkdir(parents=True, exist_ok=True)
            managers.write_bytes(b"2017.4.12f1\0" + b"UnityEngine" + b"\0" * 128)
            report = probe.scan_directory(root, 100, 8 * 1024 * 1024, 8)
        self.assertTrue(report["il2cpp_metadata"]["valid_magic"])
        self.assertEqual(report["il2cpp_metadata"]["metadata_version"], 24)
        self.assertTrue(any(item["version"] == "2017.4.12f1" for item in report["unity_version_candidates"]))

    def test_unity_path_markers(self):
        markers = probe.path_markers("romfs/Data/Managed/Metadata/global-metadata.dat")
        self.assertIn("IL2CPP global metadata", markers)

    def test_unity_text_markers_case_insensitive(self):
        markers = probe.text_markers(b"UnityPlayer UnityEngine IL2CPP Assembly-CSharp")
        self.assertIn("UnityPlayer", markers)
        self.assertIn("il2cpp", markers)
        self.assertIn("Assembly-CSharp", markers)

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
