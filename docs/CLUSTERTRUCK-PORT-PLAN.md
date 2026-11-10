# Clustertruck Android Recompilation — Port Assessment

This document records the initial assessment of this fork. It is a work plan, not a claim that Clustertruck is already supported.

## Current repository state

- This repository is a fork of `mamcjr/LASRecompAndroid`.
- The Android application and native runtime are still the Link's Awakening port. The package namespace, activity, launcher text, data paths, game-version checks and game-code library names still contain LAS-specific identifiers.
- The Android import flow currently expects an extracted game folder containing `exefs/` and `romfs/`; `android/build_gamelib.sh` takes an `exefs` folder and generates the game library from `main` and `sdk`.
- `tools/gen.py` lifts AArch64 NSO modules, but `runtime/game_addresses/versions.h` only recognizes Link's Awakening build IDs. The generator and runtime also consume title-specific patch/address tables and HLE symbol definitions.
- There is no project-owned Android GitHub Actions workflow in `.github/workflows/` at the repository root. The workflow files found in the tree belong to the vendored ImGui dependency.

## Goal

Create a genuine Clustertruck Nintendo Switch-to-Android port, targeting ARM64 Android and Vulkan. The app's intended game input is the user's own Clustertruck Switch dump in `.nsp` or `.xci` format. It must not ask for Link's Awakening files or silently treat Zelda's extracted files as Clustertruck data.

## Important feasibility gate

A Unity WebGL scan is not enough to determine how the Switch build was produced. Before adapting the recompilation pipeline, inspect metadata from the user's own Clustertruck Switch dump and establish:

1. the title's executable/module format and main executable build ID;
2. whether the executable is an NSO and whether its code is AArch64;
3. its imported system services/libraries and native dependencies;
4. the Unity version and scripting backend, if identifiable from the Switch build;
5. whether the game uses NVN and which graphics/runtime services it needs;
6. whether the existing static lifter can handle the executable's code and relocations.

If the Switch build is not compatible with the existing NSO/AArch64 static-recompilation approach, this LAS fork is not a suitable direct recompilation base. The Android shell and some Vulkan/UI code may still be reusable, but the game runtime would need a different implementation.

## Planned stages

### Stage 1 — Inspect, do not disguise
- Keep the upstream `main` branch untouched.
- Work on the separate `clustertruck-dev` branch.
- Inventory all title-specific code: build IDs, patch tables, HLE symbols, title/version checks, launcher/import logic, paths, identifiers and build scripts.
- Produce a diagnostic report from the user's own dump without committing game content, keys, title keys or decrypted copyrighted files.

### Stage 2 — Confirm the executable pipeline
- Determine whether Clustertruck's Switch executable can be processed by the existing NSO parser and lifter.
- Identify missing instructions, imports, system services and graphics calls.
- Do not add a Clustertruck build-ID table or game patches until they are grounded in the actual Clustertruck executable.

### Stage 3 — Clustertruck-specific runtime
- Replace Zelda-only address/version/patch data with verified Clustertruck-specific data, if static recompilation is viable.
- Keep the Android ARM64/Vulkan application shell where it is genuinely reusable.
- Add Clustertruck-specific input, saves, assets and runtime setup only after the executable path is understood.

### Stage 4 — Dump import
- Add an import flow for `.nsp` and `.xci` only after a lawful, device-compatible package parsing and extraction path is established.
- The picker accepting those extensions alone is not support: package parsing, integrity checks, extraction and any required user-supplied decryption material must all be handled correctly.
- Never bundle game data or keys in the repository or APK.

### Stage 5 — Build and test
- Add a reproducible Android ARM64 build workflow once dependency and private-input requirements are known.
- Test the Vulkan path separately on Adreno and Mali. A successful build does not establish that the game renders or performs well on every GPU.

## Current status

**Assessment only.** No Clustertruck executable has yet been analyzed by this repository, no Clustertruck game-code library has been generated, and no Clustertruck APK has been built or tested. The existing Unity WebGL scan is useful background but does not establish the Switch build's executable format or graphics backend.
