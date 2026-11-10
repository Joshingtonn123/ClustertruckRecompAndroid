#!/usr/bin/env python3
"""Read-only diagnostic probe for an already-extracted Nintendo Switch game dump.

This tool does not decrypt NSP/XCI/NCA files, extract copyrighted game data, or
modify the supplied files. It reports container signatures and inspects any
NSO executables that are already present in an extracted directory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from collections import Counter
from pathlib import Path
from typing import Any

try:
    from nso import Nso
except Exception as exc:  # noqa: BLE001 - reported in output when NSO parsing is requested
    Nso = None  # type: ignore[assignment,misc]
    _NSO_IMPORT_ERROR = str(exc)
else:
    _NSO_IMPORT_ERROR = ""

UNITY_PATH_MARKERS = {
    "globalgamemanagers": "Unity global game manager",
    "boot.config": "Unity boot configuration",
    "resources.assets": "Unity resources asset",
    "sharedassets": "Unity shared assets",
    "global-metadata.dat": "IL2CPP global metadata",
    "il2cpp_data": "IL2CPP data directory",
    "libil2cpp": "IL2CPP runtime library",
    "gameassembly": "Unity IL2CPP game assembly",
    "assembly-csharp": "C# Assembly-CSharp assembly",
    "mscorlib": "Mono/.NET core library",
    "unityplayer": "Unity player runtime",
    "unityfs": "UnityFS asset bundle",
    "data.unity3d": "Unity data archive",
}
UNITY_TEXT_MARKERS = (
    b"UnityPlayer",
    b"UnityEngine",
    b"UnityFS",
    b"globalgamemanagers",
    b"il2cpp",
    b"Assembly-CSharp",
    b"mono-2.0",
    b"libnn_gfx",
    b"nvn",
)
MAX_DEFAULT_FILES = 100_000
MAX_DEFAULT_SCAN_BYTES = 128 * 1024 * 1024
MAX_DEFAULT_TEXT_SAMPLE = 2 * 1024 * 1024


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_prefix(path: Path, size: int = 0x204) -> bytes:
    with path.open("rb") as stream:
        return stream.read(size)


def classify_magic(prefix: bytes) -> str:
    """Classify common Switch container/module signatures without unpacking them."""
    if prefix.startswith(b"NSO0"):
        return "Nintendo Switch NSO executable"
    if prefix.startswith(b"PFS0"):
        return "PFS0 package container (commonly NSP)"
    if prefix[0x100:0x104] == b"HEAD":
        return "XCI cartridge image header"
    if prefix.startswith((b"NCA2", b"NCA3")) or prefix[0x200:0x204] in (b"NCA2", b"NCA3"):
        return "NCA content archive (may be encrypted)"
    if prefix.startswith(b"\x7fELF"):
        return "ELF executable"
    if prefix.startswith(b"MZ"):
        return "Windows PE executable"
    if prefix.startswith(b"UnityFS"):
        return "UnityFS asset bundle"
    if prefix.startswith(b"UnityWebData"):
        return "Unity WebData archive"
    return "unknown"


def path_markers(relative_path: str) -> list[str]:
    normalized = relative_path.replace("\\", "/").lower()
    found: list[str] = []
    for marker, description in UNITY_PATH_MARKERS.items():
        if marker in normalized:
            found.append(description)
    return found


def text_markers(data: bytes) -> list[str]:
    folded = data.lower()
    found: list[str] = []
    for marker in UNITY_TEXT_MARKERS:
        if marker.lower() in folded:
            found.append(marker.decode("ascii", errors="replace"))
    return found


def inspect_nso(path: Path, relative_path: str) -> dict[str, Any]:
    result: dict[str, Any] = {
        "path": relative_path,
        "size_bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        "parsed": False,
    }
    if Nso is None:
        result["error"] = f"NSO parser unavailable: {_NSO_IMPORT_ERROR}"
        return result
    try:
        module = Nso(str(path))
        result.update({
            "parsed": True,
            "build_id": module.build_id,
            "image_size_bytes": module.size,
            "text": {"offset": module.text[0], "size": module.text[1]},
            "rodata": {"offset": module.rodata[0], "size": module.rodata[1]},
            "data": {"offset": module.data[0], "size": module.data[1]},
            "bss_size_bytes": module.bss_size,
            "needed_libraries": sorted(set(module.needed)),
            "import_count": len(module.imports()),
            "export_count": len(module.exports()),
            "relocation_types": dict(Counter(f"0x{rel[1]:x}" for rel in module.relocs)),
            "unity_strings_in_decompressed_image": text_markers(bytes(module.image)),
        })
    except Exception as exc:  # malformed, unsupported or non-NSO file; keep scanning other files
        result["error"] = f"{type(exc).__name__}: {exc}"
    return result



def parse_pfs0(path: Path, max_entries: int = 4096) -> dict[str, Any]:
    """Read a PFS0 directory table and list file metadata; never extract file payloads."""
    file_size = path.stat().st_size
    with path.open("rb") as stream:
        header = stream.read(0x10)
        if len(header) != 0x10 or header[:4] != b"PFS0":
            raise ValueError("not a PFS0 container")
        file_count, string_size, _reserved = __import__("struct").unpack_from("<III", header, 4)
        if file_count > max_entries:
            raise ValueError(f"PFS0 entry count {file_count} exceeds safety limit {max_entries}")
        table_size = file_count * 0x18
        data_start = 0x10 + table_size + string_size
        if data_start > file_size:
            raise ValueError("PFS0 directory/string table extends beyond the file")
        stream.seek(0x10)
        table = stream.read(table_size)
        strings = stream.read(string_size)
        if len(table) != table_size or len(strings) != string_size:
            raise ValueError("truncated PFS0 directory")
        entries = []
        for index in range(file_count):
            offset, size, name_offset, _entry_reserved = __import__("struct").unpack_from(
                "<QQII", table, index * 0x18
            )
            if name_offset >= string_size:
                raise ValueError(f"PFS0 entry {index} has an invalid name offset")
            name_end = strings.find(b"\0", name_offset)
            if name_end < 0:
                raise ValueError(f"PFS0 entry {index} has an unterminated name")
            name = strings[name_offset:name_end].decode("utf-8", errors="replace")
            absolute_start = data_start + offset
            if absolute_start > file_size or size > file_size - absolute_start:
                raise ValueError(f"PFS0 entry {name!r} extends beyond the file")
            stream.seek(absolute_start)
            prefix = stream.read(0x204)
            entries.append({
                "name": name,
                "size_bytes": size,
                "signature": classify_magic(prefix),
            })
        return {
            "entry_count": file_count,
            "data_start_offset": data_start,
            "entries": entries,
            "payloads_extracted": False,
        }


def inspect_single_file(path: Path) -> dict[str, Any]:
    prefix = read_prefix(path)
    kind = classify_magic(prefix)
    result: dict[str, Any] = {
        "input": str(path),
        "input_kind": kind,
        "size_bytes": path.stat().st_size,
        "extension": path.suffix.lower(),
        "package_unpacking_performed": False,
    }
    if kind == "PFS0 package container (commonly NSP)":
        try:
            result["pfs0"] = parse_pfs0(path)
            result["message"] = (
                "PFS0 directory metadata was read only. Inner content files were not extracted or decrypted."
            )
        except Exception as exc:  # malformed or truncated package; report, don't crash
            result["pfs0_error"] = f"{type(exc).__name__}: {exc}"
            result["message"] = "The PFS0 directory could not be read safely; no payloads were extracted."
    elif path.suffix.lower() in {".nsp", ".xci", ".nca", ".nsz", ".xcz"}:
        result["message"] = (
            "This is a Switch package/container input. This diagnostic identifies the outer signature only; "
            "it does not decrypt or extract package contents."
        )
    elif kind == "Nintendo Switch NSO executable":
        result["nso"] = inspect_nso(path, path.name)
    return result


def scan_directory(root: Path, max_files: int, max_scan_bytes: int, max_nso: int) -> dict[str, Any]:
    root = root.resolve()
    file_count = 0
    scanned_bytes = 0
    skipped_large_or_budget = 0
    package_entries: list[dict[str, Any]] = []
    unity_path_evidence: list[dict[str, str]] = []
    unity_text_evidence: list[dict[str, Any]] = []
    nso_paths: list[Path] = []
    magic_counts: Counter[str] = Counter()
    candidate_paths: list[str] = []
    truncated = False

    for current, dirs, files in os.walk(root):
        dirs.sort()
        files.sort()
        for filename in files:
            file_count += 1
            if file_count > max_files:
                truncated = True
                break
            path = Path(current) / filename
            try:
                rel = path.relative_to(root).as_posix()
                size = path.stat().st_size
            except OSError:
                continue

            suffix = path.suffix.lower()
            lower_name = filename.lower()
            if suffix in {".nsp", ".xci", ".nca", ".nsz", ".xcz"}:
                try:
                    kind = classify_magic(read_prefix(path))
                except OSError:
                    kind = "unreadable"
                package_entries.append({"path": rel, "size_bytes": size, "signature": kind})
                continue

            if lower_name == "main" or suffix == ".nso" or lower_name.startswith("subsdk"):
                try:
                    if read_prefix(path, 4) == b"NSO0":
                        nso_paths.append(path)
                except OSError:
                    pass

            for marker in path_markers(rel):
                unity_path_evidence.append({"path": rel, "marker": marker})

            if size == 0:
                continue
            remaining = max_scan_bytes - scanned_bytes
            if remaining <= 0 or size > MAX_DEFAULT_TEXT_SAMPLE or size > remaining:
                skipped_large_or_budget += 1
                continue
            try:
                sample = read_prefix(path, min(MAX_DEFAULT_TEXT_SAMPLE, remaining))
            except OSError:
                continue
            scanned_bytes += len(sample)
            markers = text_markers(sample)
            if markers:
                unity_text_evidence.append({"path": rel, "markers": markers})
            kind = classify_magic(sample)
            if kind != "unknown":
                magic_counts[kind] += 1
            if any(marker in rel.lower() for marker in ("exefs", "romfs", "unity", "globalgamemanagers", "boot.config")):
                candidate_paths.append(rel)
        if truncated:
            break

    # Prioritize the usual executable name, then inspect other NSO modules.
    nso_paths.sort(key=lambda p: (0 if p.name.lower() == "main" else 1, str(p).lower()))
    nso_reports = [
        inspect_nso(path, path.relative_to(root).as_posix())
        for path in nso_paths[:max_nso]
    ]

    all_path_markers = {item["marker"] for item in unity_path_evidence}
    all_text_markers = {marker for item in unity_text_evidence for marker in item["markers"]}
    combined = {m.lower() for m in all_path_markers | all_text_markers}
    il2cpp_evidence = any(
        "il2cpp" in m.lower() or "global metadata" in m.lower() or "gameassembly" in m.lower()
        for m in all_path_markers
    ) or any("il2cpp" in m or "global-metadata" in m.lower() for m in all_text_markers)
    mono_evidence = any(
        "assembly-csharp" in m.lower() or "mscorlib" in m.lower()
        for m in all_path_markers
    ) or any("Assembly-CSharp" in m or "mono-2.0" in m for m in all_text_markers)
    unity_evidence = bool(all_path_markers or all_text_markers or any("unity" in m for m in combined))
    if il2cpp_evidence and mono_evidence:
        backend = "ambiguous: both IL2CPP and Mono-related markers were found"
    elif il2cpp_evidence:
        backend = "IL2CPP-related evidence found; verify using the Switch executable and metadata"
    elif mono_evidence:
        backend = "Mono/.NET-related evidence found; verify using the Switch executable and metadata"
    elif unity_evidence:
        backend = "Unity-related evidence found, but the scripting backend is undetermined"
    else:
        backend = "undetermined; no reliable Unity backend marker found"

    main_nso = next((x for x in nso_reports if Path(x["path"]).name.lower() == "main"), None)
    main_npdm = next((root / p for p in candidate_paths if Path(p).name.lower() == "main.npdm"), None)
    npdm_info = None
    if main_npdm and main_npdm.is_file():
        npdm_info = {
            "path": main_npdm.relative_to(root).as_posix(),
            "size_bytes": main_npdm.stat().st_size,
            "sha256": sha256_file(main_npdm),
            "header_hex": read_prefix(main_npdm, 32).hex(),
        }

    return {
        "input_directory": root.name,
        "file_count_seen": min(file_count, max_files),
        "scan_truncated": truncated,
        "text_scan_bytes": scanned_bytes,
        "files_skipped_by_text_scan_limits": skipped_large_or_budget,
        "package_archives": package_entries[:200],
        "package_archive_count": len(package_entries),
        "extracted_nso_count": len(nso_paths),
        "nso_modules_inspected": nso_reports,
        "nso_modules_omitted": max(0, len(nso_paths) - len(nso_reports)),
        "main_nso": main_nso,
        "main_npdm": npdm_info,
        "unity_path_evidence": unity_path_evidence[:200],
        "unity_text_evidence": unity_text_evidence[:200],
        "unity_backend_assessment": backend,
        "unity_evidence_found": unity_evidence,
        "nvn_related_text_marker_found": any(
            marker.lower() in {"nvn", "libnn_gfx"} for marker in all_text_markers
        ),
        "magic_counts_in_sampled_files": dict(magic_counts),
        "candidate_paths": candidate_paths[:200],
        "notes": [
            "This is a read-only metadata probe. It does not decrypt, unpack, patch or modify game files.",
            "An NSO build ID and its imports are useful evidence, not proof that LASRecomp's title-specific patches and HLE runtime will work.",
            "Unity path/string markers do not prove the Switch build's scripting backend, renderer or executable layout.",
            "The earlier Unity WebGL scan describes a different build target and cannot substitute for this Switch-build report.",
            "If the input contains only NSP/XCI/NCA package files, their contents are not inspected by this tool.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="an extracted game directory, or a single .nsp/.xci/.nso file")
    parser.add_argument("--json", dest="json_path", type=Path, help="write the JSON report to this path")
    parser.add_argument("--max-files", type=int, default=MAX_DEFAULT_FILES)
    parser.add_argument("--max-scan-mib", type=int, default=MAX_DEFAULT_SCAN_BYTES // (1024 * 1024))
    parser.add_argument("--max-nso", type=int, default=32)
    args = parser.parse_args()

    if args.max_files < 1 or args.max_scan_mib < 1 or args.max_nso < 1:
        parser.error("--max-files, --max-scan-mib and --max-nso must be positive")
    if not args.input.exists():
        parser.error(f"input does not exist: {args.input}")

    if args.input.is_file():
        report = inspect_single_file(args.input)
    elif args.input.is_dir():
        report = scan_directory(
            args.input,
            args.max_files,
            args.max_scan_mib * 1024 * 1024,
            args.max_nso,
        )
    else:
        parser.error("input must be a regular file or directory")

    rendered = json.dumps(report, indent=2, sort_keys=True)
    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    if args.input.is_dir() and report.get("package_archive_count", 0) and not report.get("extracted_nso_count"):
        print(
            "\nNOTE: Package archives were found but not unpacked. Supply an already-extracted directory "
            "for executable analysis.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
