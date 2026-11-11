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
- Generic runtime diagnostics report unimplemented imports and warn when `subsdk*` modules are present, since this loader currently loads only `main` and `sdk`.
- The Android dump probe inventories an extracted `romfs/` tree (file count, total bytes, extensions and a small signature sample) without unpacking or copying its assets. Its NSO inspector reads segment ranges in file-offset order, skipping large alignment/padding gaps while keeping per-segment and decompressed-image safety limits.
- CI tests, a diagnostic app build, and a full Android app build job that validates the code-generation importer and native runtime build.

The generic generator is tested with a synthetic NSO containing a function that returns. This proves the generic generator path can emit C for an unknown build ID; it does not prove the real Clustertruck executable is supported. The actual Switch `main` and `sdk` modules still need to be analyzed. The generic profile removes one known boot-time rejection only; the loader, startup symbol, Unity-facing service imports, NVN behavior and any extra ExeFS modules remain unverified.

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

The ExeFS-only Android path copies a selected extracted `main` + `sdk` folder and invokes this generic builder. A successful result means native C/ARM64 game code was generated; **it does not mean the game can boot**. The base runtime still has title-specific startup, service/HLE, memory, filesystem, save and graphics assumptions.

## What remains before Clustertruck can boot

We still need non-sensitive metadata from the actual Switch build:

- Confirm the actual `main` and `sdk` formats, build IDs, segment sizes, imports and relocation types.
- Run the real NSOs through the generator and fix unsupported instructions/relocations without applying unrelated title patches.
- Identify the imported system services and the game entry/startup path.
- Identify the actual graphics interface, then determine what renderer work or reuse is appropriate.
- Replace the title-specific boot/runtime, memory, filesystem, audio/input and save assumptions.
- Only after the native app builds and the game runtime is adapted can we test an actual boot on Android.

## Branch policy

All Clustertruck experiments stay on `clustertruck-dev`. The upstream-derived `main` branch is intentionally untouched.

The diagnostic and full-application build artifacts are temporary GitHub Actions artifacts. Open the [Clustertruck workflow runs](https://github.com/Joshingtonn123/ClustertruckRecompAndroid/actions/workflows/clustertruck-probe.yml?query=branch%3Aclustertruck-dev) to access recent builds. A full-app development APK, once CI succeeds, is for testing the ExeFS-only code-generation path; it is **not** a playable Clustertruck release.
