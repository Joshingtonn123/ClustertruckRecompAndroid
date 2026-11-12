from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ClustertruckNativeBootTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_generic_android_setup_exposes_static_and_native_paths(self):
        launcher = self.read("runtime/ui/launcher.mm")
        self.assertIn("Both execution paths are exposed", launcher)
        self.assertIn("#ifdef CLUSTERTRUCK_GENERIC_TITLE", launcher)
        self.assertIn('static const char *const modes[] = {"Recompiled", "Native (experimental)"};', launcher)
        self.assertIn('static const char *const modes[] = {"Recompiled", "Native (experimental)"};', launcher)
        self.assertIn("translate the Switch ARM64 modules to C", launcher)
        self.assertIn("run the original ARM64 modules directly", launcher)
        self.assertIn('if ((m == 1) != g_settings.native_code)', launcher)

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

    def test_android_saf_import_accepts_clustertruck_nso(self):
        launcher = self.read("runtime/ui/launcher.mm")
        self.assertIn('if (env->GetArrayLength(head) < 0x60) return JNI_FALSE;', launcher)
        self.assertIn('if (memcmp(hdr, "NSO0", 4)) return JNI_FALSE;', launcher)
        self.assertIn('#ifdef CLUSTERTRUCK_GENERIC_TITLE\n    char build_id[41];', launcher)
        self.assertIn('generic Clustertruck NSO accepted', launcher)
        self.assertIn('return JNI_TRUE;', launcher)

    def test_android_saf_import_does_not_force_valid_generic_nso_to_exefs_only(self):
        java = self.read("android/lasrecomp/app/src/main/java/com/lasrecomp/LASRecompActivity.java")
        launcher = self.read("runtime/ui/launcher.mm")
        self.assertIn("if (!nativeBuildIdOk(head)) compileOnly = true;", java)
        self.assertIn('if (memcmp(hdr, "NSO0", 4)) return JNI_FALSE;', launcher)
        self.assertIn("return JNI_TRUE;", launcher)
        self.assertIn("generic Clustertruck NSO accepted", launcher)

    def test_unity_romfs_in_place_and_loose_file_policy(self):
        launcher = self.read("runtime/ui/launcher.mm")
        self.assertIn('file_exists(path + "/Data")', launcher)
        self.assertIn('return rel && *rel;', launcher)
        self.assertIn('file_exists(path + "/region_common")', launcher)  # LAS behavior remains in its branch

    def test_launcher_finds_unity_data_folder_and_checks_it(self):
        launcher = self.read("runtime/ui/launcher.mm")
        self.assertIn('if (romfs.empty() && is_dir(dir + "/Data")) romfs = dir;', launcher)
        self.assertIn('#ifdef CLUSTERTRUCK_GENERIC_TITLE', launcher)
        self.assertIn('file_exists(std::string(linked) + "/Data")', launcher)
        self.assertIn('file_exists(root + "/romfs/Data")', launcher)
        self.assertIn('file_exists(root + "/romfs/region_common")', launcher)  # preserved for upstream LAS profiles


if __name__ == "__main__":
    unittest.main()
