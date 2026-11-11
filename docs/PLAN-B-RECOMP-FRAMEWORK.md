# Plan B: Clustertruck recompilation framework choice

## Decision

Keep the Android application and ARM64 runtime foundation already present in this repository, and reuse compatible generic improvements from other static-recompilation projects. Do not replace the whole project with a different game's runtime just because that project has more features.

The current Android base is the LASRecompAndroid fork. Its Android activity, storage picker, SDL/JNI integration, Android build setup, ARM64 guest-memory layout and native-code path are more relevant to this target than a Windows/Linux-only project.

## What was compared

| Project | Useful parts | Why it is not the whole Clustertruck base |
|---|---|---|
| [LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid) / [LASRecomp](https://github.com/peiikai/LASRecomp) | Existing Android app shell, ARM64 native path, NSO generator, SDL integration, Vulkan backend, Horizon-style runtime | The original game-specific address tables, HLE patches, and boot assumptions must be separated from generic code |
| [SavageWindRecomp](https://github.com/Revivedx/SavageWindRecomp) | A more mature generic NSO loader, packed relative relocations, GNU-hash symbol counting, Horizon kernel/IPC patterns, and NVN rendering research | Its game implementation is for Breath of the Wild on Windows/Linux and its documented target uses Vulkan 1.3; it is not an Android 11/Vulkan 1.1 drop-in |
| [smo-recomp](https://github.com/Coz-0/smo-recomp) | Clear separation of core startup, SDK, file system, input, audio and NVN/Vulkan layers; useful architecture reference | Its current target is Windows x64 and its code is GPL-3.0, so wholesale copying would impose different licensing obligations and still would not create an Android ARM64 port |

## Reuse completed on clustertruck-dev

The MIT-licensed SavageWindRecomp loader format logic has been adapted for:

- DT_GNU_HASH: count dynamic symbols when a SysV DT_HASH table is absent.
- DT_RELR / DT_RELRSZ: apply packed relative relocations in the native loader.
- Android NSO inspection: identify GNU versus SysV hash tables and count packed relative relocation targets.
- Generator input: convert each decoded packed relative relocation to the existing RELA-style tuple so current function-boundary and code-pointer analysis sees it.

The adapted loader adds bounds and count limits. Synthetic fixtures test GNU-hash-only modules and packed relative relocations. Attribution is in THIRD_PARTY_NOTICES.md.

## Clustertruck-specific facts still controlling the port

The inspected dump report lists main, sdk, rtld, and subsdk0. The current generic loader maps main, sdk, and optional subsdk0; startup is handled by the host runtime rather than loading rtld as an ordinary game module. The report's observed RELA relocation types are 0x101, 0x401, 0x402 and 0x403, which the current loader already recognizes. No DT_RELR use was established by that report, so the new support is compatibility groundwork, not a claimed fix for an observed Clustertruck failure.

The report also shows Unity assets and subsdk0 imports associated with Nintendo SDK, Android framework and NVIDIA/Tegra multimedia APIs. Those are the actual next runtime questions. We should implement an HLE service only after its import contract and call behavior are established; returning success from an unknown stub would only hide a crash.

## Not implemented or verified

- No actual Clustertruck NSO payload has been included in the repository or run through the full generator. The shared report is metadata, not executable code.
- Unity startup, Clustertruck-specific service behavior, renderer compatibility, audio and touch/gameplay have not been proven.
- The desktop Vulkan renderer from another title has not been transplanted. Android device support and Vulkan 1.1 compatibility need separate verification.
- The app build remains guarded; the current Actions workflow builds the diagnostic probe and dry-runs the adapted app configuration, not a playable Clustertruck APK.

## Next technical order

1. Run the updated parser and generator against the user's own extracted NSO modules and capture the exact unsupported instruction/import/module list.
2. Resolve the missing import contracts and module initialization order from the actual dump and call sites.
3. Build a title-neutral Unity/Android/Tegra service compatibility layer where the evidence requires it.
4. Validate an NVN-to-Vulkan 1.1 route for Mali separately from CPU startup.
5. Only then enable a real game boot attempt and add touch controls, audio and performance work.

main is not part of this work. Changes stay on clustertruck-dev.
