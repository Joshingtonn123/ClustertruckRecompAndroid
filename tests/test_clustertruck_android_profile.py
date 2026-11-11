from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


class ClustertruckAndroidProfileTests(unittest.TestCase):
    def test_android_app_has_its_own_identity(self):
        gradle = read("android/lasrecomp/app/build.gradle.kts")
        manifest = read("android/lasrecomp/app/src/main/AndroidManifest.xml")
        self.assertIn('applicationId = "com.clustertruck.recomp"', gradle)
        # Keep the Java/JNI namespace stable while installing as a separate app.
        self.assertIn('namespace = "com.lasrecomp"', gradle)
        self.assertIn('versionName = "0.1.0"', gradle)
        self.assertIn('val prepareClustertruck by tasks.registering', gradle)
        self.assertNotIn("prepareLasRecomp", gradle)
        self.assertIn('android:label="Clustertruck Recomp (Experimental)"', manifest)
        self.assertIn('android:icon="@drawable/ic_clustertruck"', manifest)

    def test_clustertruck_icon_and_shortcut_match_the_new_package(self):
        icon = read("android/lasrecomp/app/src/main/res/drawable/ic_clustertruck.xml")
        shortcut = read("android/lasrecomp/app/src/main/res/xml/shortcuts.xml")
        self.assertIn("<vector", icon)
        self.assertIn('android:targetPackage="com.clustertruck.recomp"', shortcut)
        self.assertIn('android:action="com.clustertruck.recomp.action.SYSTEM_DRIVER"', shortcut)
        self.assertIn('android:targetClass="com.lasrecomp.LASRecompActivity"', shortcut)

    def test_on_device_generator_bundle_is_generic_not_zeldaspecific(self):
        gradle = read("android/lasrecomp/app/build.gradle.kts")
        generator = read("tools/gen.py")
        self.assertIn('"gen.py", "lifter.py", "analyze.py", "nso.py", "passes.py", "simd_c.py"', gradle)
        self.assertNotIn("tools/patches.py", gradle)
        self.assertNotIn("tools/game_addresses.py", gradle)
        self.assertNotIn("tools/rando/patch_variants.json", gradle)
        self.assertNotIn("gameTables", gradle)
        self.assertIn("ap.add_argument('--generic'", generator)
        self.assertIn("if args.generic:", generator)
        self.assertIn("RANDO_CODE.clear()", generator)

    def test_generic_profile_keeps_title_specific_features_disabled(self):
        cmake = read("android/lasrecomp/app/src/main/cpp/CMakeLists.txt")
        menu = read("runtime/ui/menu.mm")
        launcher = read("runtime/ui/launcher.mm")
        self.assertIn("CLUSTERTRUCK_GENERIC_TITLE=1", cmake)
        self.assertIn("#ifdef CLUSTERTRUCK_GENERIC_TITLE", menu)
        self.assertIn("if (i != 0 && i != 1 && i != 8 && i != 9) continue;", menu)
        self.assertIn("#if defined(__ANDROID__) && !defined(CLUSTERTRUCK_GENERIC_TITLE)", launcher)
        self.assertIn("Clustertruck code compiled; Unity startup, Switch services and game rendering are not integrated yet", launcher)

    def test_missing_sdk_exception_stack_import_has_a_reset_safe_hle(self):
        source = read("runtime/hle/exception.c")
        loader = read("runtime/loader.c")
        cmake = read("android/lasrecomp/app/src/main/cpp/CMakeLists.txt")
        makefile = read("Makefile")

        self.assertIn('HLE_FUNC("nnosGetDefaultUserExceptionStackForApplication"', source)
        self.assertIn("hle_alloc(stack_size + 0x1000)", source)
        self.assertIn("0x10000", source)
        self.assertIn("allocation + 0xFFFu", source)
        self.assertIn("RT_ON_RESET(user_exception_stack_reset)", source)
        self.assertIn("runtime/hle/*.c", makefile)
        self.assertIn("runtime/hle/*.c", cmake)
        self.assertLess(loader.index("HostFn impl = hle_lookup_impl(name);"),
                        loader.index("if ((s->info >> 4) == 2) return 0;"))

    def test_import_flow_is_clustertruck_scoped_and_does_not_claim_package_extraction(self):
        activity = read("android/lasrecomp/app/src/main/java/com/lasrecomp/LASRecompActivity.java")
        launcher = read("runtime/ui/launcher.mm")
        self.assertIn('getSharedPreferences("clustertruck"', activity)
        self.assertIn('addGameSourceButton(content, dialog, "XCI package"', activity)
        self.assertIn('addGameSourceButton(content, dialog, "NSP package"', activity)
        self.assertIn("NxDump handles ", activity)
        self.assertIn("XCI/NSP packages must be extracted with NxDump first.", launcher)
        self.assertIn("does NOT boot or play Clustertruck yet.", activity)


if __name__ == "__main__":
    unittest.main()
