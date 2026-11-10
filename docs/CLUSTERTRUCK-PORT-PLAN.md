# Clustertruck Android Recompilation — Port Assessment

This document records the feasibility work for this fork. It is a work plan and diagnostic record, not a claim that Clustertruck is already supported.

## Current repository state

- This repository is a fork of `mamcjr/LASRecompAndroid`.
- The Android application and native runtime are still the Link's Awakening port. The package namespace, activity, launcher text, data paths, game-version checks and game-code library names still contain LAS-specific identifiers.
- The Android import flow expects an extracted game directory containing `exefs/` and `romfs/`. `android/build_gamelib.sh` takes an `exefs` directory and generates game code from the Switch `main` and `sdk` NSO modules.
- `tools/gen.py` lifts AArch64 NSO modules, but `runtime/game_addresses/versions.h` recognizes only Link's Awakening build IDs. The generator/runtime also consume title-specific patch/address tables and HLE symbol definitions.
- The Android launcher explicitly treats `.nsp`, `.xci`, `.nca`, `.nsz` and `.xcz` as package files it cannot use. It does not unpack those containers.
- There is no project-owned Android GitHub Actions workflow at the repository root. Existing workflow files in the tree belong to vendored dependencies.

## Goal

Create a genuine Clustertruck Nintendo Switch-to-Android port, targeting ARM64 Android and Vulkan. The intended game input is the user's own Clustertruck Switch dump in `.nsp` or `.xci` format. It must never ask for Link's Awakening files or silently treat Zelda's extracted files as Clustertruck data.

## Feasibility references

The earlier Unity WebGL scan is evidence about a WebGL build only. It does not establish the Switch build's executable format, Unity version, scripting backend, renderer or system-service requirements.

A useful comparison is [Cruis'n Blast Recomp](https://github.com/darkuni/cruisn-blast-recomp). Its project README identifies that Switch title as Unity 2019.4.18f1 with IL2CPP metadata v24.4 and NVN graphics, and documents a static-recompilation pipeline built around its own game profile and runtime. Its shipped target is desktop Windows/Linux, not Android. It demonstrates that Unity/IL2CPP Switch games can have a different analysis and runtime path from this Zelda-focused base; it does **not** prove Clustertruck uses the same Unity version/backend or that its code can be reused directly.

## Implemented diagnostic tooling

### Read-only Python probe

`tools/clustertruck_probe.py` can inspect an already-extracted folder or identify the outer signature of a selected `.nsp` / `.xci` / `.nca` file.

For extracted content it reports:
- detected NSO modules and, where the existing parser can read them, build IDs, segment sizes, imports, exports and relocation types;
- the `main.npdm` file's size and SHA-256 digest;
- Unity-related filenames and strings, including markers that may indicate IL2CPP or Mono;
- package files that remain in the folder and likely extraction-layout candidates.

It is diagnostic only. It does not decrypt or unpack Switch packages, modify files, infer a supported game version, or claim a Unity backend from weak string evidence.

Run it with Python and the pinned dependency:

```sh
python -m pip install -r tools/requirements-probe.txt
python tools/clustertruck_probe.py /path/to/already-extracted/Clustertruck --json clustertruck-switch-report.json
```

### Android 11+ dump probe app

A separate app is at `android/clustertruck-probe/`. It uses Android's document picker so the user can select a package file or an already-extracted folder without granting broad storage access.

- For PFS0/NSP it reads only the package directory table and lists names/sizes; it does not read NCA payloads.
- For XCI it reads the root HFS0 directory and visible `secure` / `normal` / `update` / `logo` partition directory entries, including NCA filenames, sizes and first-byte signatures where available. It does not decrypt NCA payloads or extract game content.
- For an already-extracted directory it looks for NSO0 headers and reports build IDs, segment metadata, `main.npdm` SHA-256, and Unity/IL2CPP/Mono path/string clues.
- Reports can be copied or shared from the app.

**The probe app is not the game.** It contains no Clustertruck game code/data, does not decrypt or extract the dump, and cannot launch Clustertruck.

### CI and APK build status

`.github/workflows/clustertruck-probe.yml` has two jobs:
- `probe-tests`: Python syntax checks and unit tests for package signatures, PFS0 directory parsing, ELF/NSO markers and Unity evidence.
- `android-dump-probe`: builds the diagnostic APK with Gradle for minSdk 30 (Android 11), then uploads it as the `clustertruck-dump-probe-debug` artifact.

The successful run is [workflow run 42807069464](https://github.com/Joshingtonn123/ClustertruckRecompAndroid/actions/runs/42807069464). The APK artifact is temporary and retained by GitHub Actions for a limited time.

## Engineering stages

### Stage 1 — Inspect, do not disguise
- Keep upstream `main` untouched.
- Inventory all title-specific code: build IDs, patch tables, HLE symbols, title/version checks, launcher/import logic, paths, identifiers and build scripts.
- Analyze metadata from the user's own Switch dump without committing game content, decryption keys, title keys or decrypted copyrighted files.

### Stage 2 — Confirm the executable pipeline
- Determine whether Clustertruck's Switch executable is an NSO/AArch64 module compatible with the existing parser and lifter.
- Identify imports, system services, unsupported instructions, relocations and graphics calls.
- Establish the Unity version and scripting backend from Switch-build evidence, not from the separate WebGL scan.
- Do not add a Clustertruck build-ID table or title patches until they are grounded in the actual Clustertruck executable.

### Stage 3 — Clustertruck-specific runtime
- Replace Zelda-only address/version/patch data with verified Clustertruck-specific data, if static recompilation is viable.
- Keep the Android ARM64/Vulkan application shell only where it is genuinely reusable.
- Add Clustertruck-specific input, saves, assets and runtime setup only after the executable path is understood.

### Stage 4 — Dump import
- Add `.nsp` / `.xci` import only after a lawful, device-compatible package parsing and extraction path is established.
- A file picker accepting those extensions is not support: package parsing, integrity checks, extraction and any required user-supplied decryption material must all be handled correctly.
- Never bundle game data or keys in the repository or APK.

### Stage 5 — Build and test
- Add a reproducible ARM64 Android game build workflow after dependencies and game-specific inputs are known.
- Test the Vulkan path separately on Adreno and Mali. A successful build does not establish that the game renders or performs well on every GPU.

## Current status

**Diagnostic tooling and the earlier Android 11+ probe APK are built and CI-tested. A newer XCI HFS0 directory-inspection change is committed but still needs a fresh Android CI build and a test against the user's XCI. Clustertruck game compatibility is not yet established.** No Clustertruck Switch executable has yet been analyzed by this repository, no Clustertruck game-code library has been generated, and no playable Clustertruck APK has been built or tested. The next decision depends on a report from the actual Switch build.


## Reusable foundation already present in the untouched `main` branch

The upstream-derived `main` branch is still the LASRecompAndroid base; it has not been changed for this Clustertruck work. Its current README and source describe a substantial Android recompilation stack, including several improvements explicitly adapted from BOTWRecompAndroid. This is a stronger starting point than a minimal LAS snapshot, but it remains a Link's Awakening implementation.

### Candidate components to preserve or adapt

- **Android platform shell:** Android activity/lifecycle, SDL3 window/input/audio integration, Gradle/NDK setup, touch controls, controller support, settings and file-picker patterns.
- **Graphics foundation:** Vulkan device/swapchain management, NVN command translation, shader translation, render-thread scheduling, render-target management and compatibility fallbacks for Vulkan 1.1/1.2 drivers. These are candidates only if Clustertruck's Switch renderer is NVN-compatible.
- **ARM64 recompilation infrastructure:** NSO/AArch64 parsing and lifting, native code generation, memory/runtime support and HLE scaffolding. These can be evaluated only after the actual Clustertruck executable and imports are identified.
- **BOTW-derived Android improvements already documented upstream:** native ARM64 game-code mode, GPU arena/copy tracking for drivers without direct GPU heap mapping, classic render-pass fallback, and other Mali/stock-driver paths. We should reuse these existing implementations where their assumptions match Clustertruck, rather than reimplementing them.
- **Build and diagnostics:** Android build configuration, dependency build scripts, CI patterns, logging and performance instrumentation.

### What cannot be carried over unchanged

The current game-version/build-ID recognition, Zelda address and patch tables, Zelda-specific HLE assumptions, save/data formats, game setup validation, launcher text and game-specific runtime behavior must not be treated as Clustertruck support. A Unity title may also require a different executable/runtime integration path from Link's Awakening. The WebGL scan is not enough to decide whether the Switch build is Mono or IL2CPP.

### Reuse decision for Clustertruck

1. Keep `main` untouched and make all experiments on `clustertruck-dev`.
2. Preserve the reusable Android/Vulkan/SDL infrastructure while mapping each dependency to a confirmed Clustertruck requirement.
3. Do not transplant BOTW or TOTK title-specific code or game data. Reuse only general engine/platform improvements whose interfaces and assumptions match.
4. First obtain non-sensitive metadata from a legitimately extracted executable (format, architecture, build ID, imports, Unity version/backend and graphics interface). The current XCI report only describes the outer HFS0 directory and cannot answer these questions.
5. Only then decide whether the LAS static recompiler can lift Clustertruck's executable, whether the NVN renderer can be reused, and which title-specific runtime pieces must be replaced.

### Mali G85 and the user's Mali-G76 MC4

The user's Helio G90T uses a Mali-G76 MC4, not the Mali-G52 MC2 commonly paired with Helio G85. The G76 MC4 is a different and generally stronger GPU configuration, so a demonstrated working path on a G85 would be encouraging evidence, not a guarantee. Vulkan extension support, driver behavior, memory bandwidth, CPU load, game workload and thermal limits still determine whether it boots and how well it runs. The upstream LAS README currently reports 20–30 fps on a Mali-G615 and says that more real Mali devices still need confirmation; that is not a benchmark for this Clustertruck port or a verified G85/G76 result.
