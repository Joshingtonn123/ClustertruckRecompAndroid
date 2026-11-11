# Clustertruck Switch → Android (experimental)

**Status: generic NSO code generation is integrated in the development branch; target compatibility and game boot are not yet verified. No playable Clustertruck APK exists yet.**

This branch explores a genuine Nintendo Switch 1 → Android recompilation of Clustertruck. The goal is to recompile the Switch executable into native ARM64 code and eventually run it on Android. The current work is focused on proving the executable pipeline, not graphics performance.

> [!WARNING]
> This repository began as a fork of [LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid), which is specifically for *The Legend of Zelda: Link's Awakening*. The reusable AArch64 generator and some Android build infrastructure are being adapted, but the existing game runtime, game-version tables, patches, HLE, filesystem and original launcher are still title-specific. Merely accepting Clustertruck files does not make the base runtime compatible.

## Current engineering output

The `clustertruck-dev` branch now contains:

- A generic NSO-to-C generator mode which bypasses Zelda-only build-ID and instruction-patch assumptions for the main module.
- The Android runtime is compiled with a Clustertruck generic-title profile: it accepts a valid NSO main module without selecting the fork's Zelda address tables, and disables Zelda version matching / hot-function hints. The generic address table is all-zero, so title-specific hooks are not safe to call.
- Android game-code builder integration that invokes generic mode for the main NSO and avoids Zelda-specific hot-function hints for an unknown title.
- An Android generic-title import path which accepts an extracted ExeFS folder and preserves a sibling `romfs/` folder or `romfs.bin` for subsequent asset analysis. The current generic step still compiles executable code only and deliberately does not attempt to boot the result.
- A Switch-only read-only probe for visible XCI/NSP directory metadata, NSO module headers/build IDs, and `main.npdm`.
- The generic runtime now generates and links optional `subsdk0` code alongside `main` and `sdk`; its exports are loaded before relocations so other modules can resolve symbols from it. Other `subsdk*` modules remain unsupported.
- The NSO loader and Android dump inspector recognize SysV and GNU dynamic symbol hash tables, and the loader handles packed `DT_RELR` relative relocations. These format-handling ideas are adapted from the MIT-licensed [SavageWindRecomp](https://github.com/Revivedx/SavageWindRecomp) loader, with extra module bounds checks and synthetic tests.
- The Android dump probe inventories an extracted `romfs/` tree (file count, total bytes, extensions and a small signature sample) without unpacking or copying its assets. Its NSO inspector reads segment ranges in file-offset order, skipping large alignment/padding gaps while keeping per-segment and decompressed-image safety limits.
- CI tests and a separate Clustertruck dump-probe diagnostic app build. The LAS-based full-app packaging job is intentionally excluded from this Clustertruck workflow.

The generic generator is tested with a synthetic NSO containing a function that returns. This proves the generic generator path can emit C for an unknown build ID; it does not prove the real Clustertruck executable is supported. The actual Switch executable payloads have not yet been supplied for direct code-generation testing. The generic loader now maps `main`, `sdk`, and optional `subsdk0`, but the real module startup order, unresolved imports, Unity-facing services, NVN behavior and any additional ExeFS modules remain unverified.

## Clustertruck-only Android app foundation

The LAS-derived Android app source has now been adapted into a separate Clustertruck app profile. Its source still sits in the historical `android/lasrecomp/` directory, but Gradle installs it as **Clustertruck Recomp** (`com.clustertruck.recomp`). The internal Java namespace `com.lasrecomp` is deliberately retained for the existing SDL/JNI native bindings; it is not the installed Android package ID.

The Clustertruck profile now has its own launcher icon, app identity, data/settings/log names and foreground notification. The Android setup flow is worded for a Clustertruck Switch dump. XCI/NSP choices still hand extraction to NxDump; this app does not yet parse or decrypt those containers itself.

When the Android generator dependencies are built, the Clustertruck app package is configured to bundle the generic NSO generator and compiler runtime. It does not package the Zelda build-ID tables, instruction patch module, version-specific hot-function list or randomizer patch data. The Android runtime also hides Zelda-only pages such as Save Editor, Randomizer, Warp, Cheats, Gameplay and Cosmetics; only common Controls, Video and Audio pages remain exposed.

**This is an adapted app foundation, not a playable game release.** The generic NSO recompilation step can generate native code, but Unity startup, Switch service emulation, game-specific memory/filesystem integration and Clustertruck rendering are not implemented. The app's boot gate remains in place so it will not pretend the game is ready.

The lightweight Clustertruck workflow builds the diagnostic probe APK, runs source/generator tests, and performs a Gradle dry-run of the Clustertruck app project. It does not assemble the full recompilation app yet; the probe APK is not the Clustertruck app.

## Diagnostic app

A separate Android 11+ probe app is at [`android/clustertruck-probe/`](android/clustertruck-probe/). It is not the recompilation app and cannot run Clustertruck.

1. **Inspect NSP** — reads the outer PFS0 directory and lists visible filenames and sizes. It does not inspect, decrypt or extract NCA payloads.
2. **Inspect XCI** — reads the cartridge header and visible HFS0 directory metadata for its partitions, listing filenames, sizes and visible file signatures. It does not decrypt or extract NCA payloads.
3. **Inspect standalone NSO** — reports its visible build ID, flags, BSS size, and text/rodata/data segment sizes and offsets.
4. **Scan an already-extracted Switch folder** — looks for the `main`, `sdk`, `rtld`, `subsdk*` NSO module headers and hashes `main.npdm`. It reads only those headers and the NPDM file for hashing; it does not inspect engine data, unpack or modify files.
5. **RomFS inventory** — when the selected extracted root contains `romfs/`, counts files and bytes, groups extensions, samples paths and checks a small prefix of up to 32 files for familiar asset signatures. It does not unpack the assets.
6. **Share report** — copies or shares the text report so module metadata can be assessed without uploading game files.

The probe does not contain game files or keys, decrypt packages, modify the dump, or run the game. An NSP/XCI filename listing alone cannot prove that the executable is supported. A report from the extracted game root containing both `exefs/` and `romfs/` is the most useful input.

## Generic NSO code generation

`tools/gen.py --generic` accepts an arbitrary main-module build ID and disables the Zelda-specific address/patch and randomizer substitutions. The Android on-device builder passes this option for the main NSO; its SDK module continues through the existing HLE-symbol generator path. When the main build ID is not in the Zelda version table, the Android builder also disables Zelda-derived hot-function address hints.

The Android importer accepts an extracted root with `exefs/` and, when available, preserves `romfs/` or `romfs.bin`. The builder recompiles `main` and `sdk`, plus `subsdk0` when it exists. A successful result means native C/ARM64 game code was generated; **it does not mean the game can boot**. The base runtime still has title-specific startup, service/HLE, memory, filesystem, save and graphics assumptions.

## What remains before Clustertruck can boot

We still need non-sensitive metadata from the actual Switch build:

- Confirm the real `main`, `sdk` and `subsdk0` module metadata and run the actual executable payloads through code generation.
- Run the real NSOs through the generator and fix unsupported instructions/relocations without applying unrelated title patches.
- Identify the imported system services and the game entry/startup path.
- Identify the actual graphics interface, then determine what renderer work or reuse is appropriate.
- Replace the title-specific boot/runtime, memory, filesystem, audio/input and save assumptions.
- Only after the native app builds and the game runtime is adapted can we test an actual boot on Android.

## Branch policy

All Clustertruck experiments stay on `clustertruck-dev`. The upstream-derived `main` branch is intentionally untouched.

The dump-probe APK and test outputs are temporary GitHub Actions artifacts. Open the [Clustertruck workflow runs](https://github.com/Joshingtonn123/ClustertruckRecompAndroid/actions/workflows/clustertruck-probe.yml?query=branch%3Aclustertruck-dev) to access recent builds. The separate probe APK only inspects extracted files; it is **not** a recompilation runtime or a playable Clustertruck release. The LAS-based full-app build is deliberately not part of this workflow.
