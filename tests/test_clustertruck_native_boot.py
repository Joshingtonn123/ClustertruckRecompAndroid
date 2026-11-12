from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ClustertruckNativeBootTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_native_mode_defaults_on_for_generic_android_target(self):
        settings = self.read("runtime/settings.c")
        self.assertIn("#ifdef CLUSTERTRUCK_GENERIC_TITLE\n    // This target has no validated static game-code build yet. Start with its guarded ARM64 native path.\n    .native_code = true,", settings)

    def test_native_mode_never_applies_zelda_fix_sites_to_clustertruck(self):
        native = self.read("runtime/native.c")
        self.assertIn("#ifndef CLUSTERTRUCK_GENERIC_TITLE\n    if (is_main) room += NFIX * 128;\n#endif", native)
        self.assertIn("#ifdef CLUSTERTRUCK_GENERIC_TITLE\n    // Clustertruck has no verified Zelda address map.", native)
        self.assertIn("generic Clustertruck mode: Zelda-specific native fixes skipped", native)
        self.assertIn("#ifndef CLUSTERTRUCK_GENERIC_TITLE\n    if (is_main) {  // (first: a seed's word is the instruction there now)", native)

    def test_launcher_accepts_generic_main_and_sdk_nso_modules(self):
        launcher = self.read("runtime/ui/launcher.mm")
        self.assertIn("#ifdef CLUSTERTRUCK_GENERIC_TITLE\n    auto valid_nso", launcher)
        self.assertIn('fread(header, 1, sizeof header, f) == sizeof header && !memcmp(header, "NSO0", 4)', launcher)
        self.assertIn('bool sdk_ok = valid_nso(exefs + "/sdk");', launcher)
        self.assertIn("return main_ok && sdk_ok;", launcher)

    def test_launcher_checks_unity_data_instead_of_zelda_region_common(self):
        launcher = self.read("runtime/ui/launcher.mm")
        self.assertIn('file_exists(std::string(linked) + "/Data")', launcher)
        self.assertIn('file_exists(root + "/romfs/Data")', launcher)
        self.assertIn('file_exists(root + "/romfs/region_common")', launcher)  # preserved for upstream LAS profiles


if __name__ == "__main__":
    unittest.main()
