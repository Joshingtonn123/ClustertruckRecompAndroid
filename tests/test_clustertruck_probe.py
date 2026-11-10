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
        self.assertIn("does not decrypt or extract", result["message"])


if __name__ == "__main__":
    unittest.main()
