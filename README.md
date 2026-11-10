# Clustertruck Switch → Android (experimental)

**Status: research and diagnostics only — no playable Clustertruck APK exists yet.**

This branch explores whether Clustertruck's Nintendo Switch build can be statically recompiled into a native ARM64 Android app using Vulkan. The intended game input is the user's own Clustertruck dump. This project must not require Link's Awakening files.

> [!WARNING]
> This repository began as a fork of [LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid), which is specifically for *The Legend of Zelda: Link's Awakening*. The existing game runtime, game-version tables, patches and original launcher are still Zelda-specific. Renaming the app or changing its file picker would not make it a Clustertruck port. Do not use the existing LAS game launcher to import Clustertruck files.

## Current useful output

The `clustertruck-dev` branch contains a separate Android 11+ diagnostic app:

- [Android probe source](android/clustertruck-probe)
- [Port assessment and next steps](docs/CLUSTERTRUCK-PORT-PLAN.md)
- [Read-only Python diagnostic](tools/clustertruck_probe.py)
- [GitHub Actions checks and Android probe build](.github/workflows/clustertruck-probe.yml)

The latest successful workflow produces an artifact named **`clustertruck-dump-probe-debug`**. Open the [Clustertruck workflow runs](https://github.com/Joshingtonn123/ClustertruckRecompAndroid/actions/workflows/clustertruck-probe.yml?query=branch%3Aclustertruck-dev), select the newest successful run, and download the artifact from its Artifacts section. The ZIP contains `app-debug.apk`; it is a diagnostic app for Android 11 or later, **not a playable game build**. GitHub retains each artifact for 7 days.

### What the diagnostic app does

1. **Inspect .NSP** — reads the outer PFS0 directory and lists visible filenames and sizes. It does not open, decrypt or extract the NCA payloads.
2. **Inspect .XCI** — checks the cartridge image header only. It does not unpack its partitions.
3. **Inspect an already-extracted folder** — looks for NSO executable headers/build IDs, segment metadata, `main.npdm`, and Unity/IL2CPP/Mono-related filenames and strings.
4. **Scan an already-extracted folder** — choose Settings → Scan an already-extracted game folder. The app recursively checks a bounded number of filenames and small file samples, reports visible NSO build IDs and Unity/IL2CPP/Mono clues, and computes a hash for `main.npdm` when present. It does not decrypt, unpack, patch, or modify files.
5. **Share report** — copies or shares the text report so the Switch executable can be assessed without uploading the game dump.

The tool is read-only. It does not contain game files or keys, decrypt packages, modify the dump, or run the game. An NSP/XCI filename listing by itself is not enough to prove the executable is compatible; an already-extracted folder provides more useful evidence. The folder scanner only inspects content that is already present in the folder you select; it does not create that folder or perform package extraction.

## What still needs to be established

Before implementing the game runtime, the actual Switch build must be inspected to determine its executable format, Unity version, scripting backend, imports/system services, graphics interface, and compatibility with the available AArch64 recompilation pipeline.

A separate public reference, [Cruis'n Blast Recomp](https://github.com/darkuni/cruisn-blast-recomp), documents a Unity IL2CPP + NVN Switch game and a desktop static-recompilation pipeline. It is a useful technical comparison, not a ready-made Android port. Clustertruck's Switch build still needs its own evidence and title-specific work.

## Development branch

Work is being done on `clustertruck-dev`. The upstream-derived `main` branch is left untouched until a real Clustertruck implementation is ready.
