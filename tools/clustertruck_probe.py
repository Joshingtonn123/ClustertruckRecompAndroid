#!/usr/bin/env python3
"""Read-only diagnostic probe for an already-extracted Nintendo Switch game dump.

This tool does not decrypt NSP/XCI/NCA files or modify the supplied files. It
reports outer container signatures and inspects NSO executables already present
in an extracted directory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
from collections import Counter
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

try:
    from nso import Nso
except Exception as exc:  # noqa: BLE001 - reported when NSO parsing is requested
    Nso = None  # type: ignore[assignment,misc]
    _NSO_IMPORT_ERROR = str(exc)
else:
    _NSO_IMPORT_ERROR = ""

MAX_DEFAULT_FILES = 100_000


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
    return "unknown"


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
            "symbol_hash_style": module.symbol_hash_style,
            "packed_relative_relocation_count": len(module.packed_relative_relocations),
            "relocation_types": dict(Counter(f"0x{rel[1]:x}" for rel in module.relocs)),
        })
    except Exception as exc:  # malformed or unsupported file; keep scanning
        result["error"] = f"{type(exc).__name__}: {exc}"
    return result


def parse_pfs0(path: Path, max_entries: int = 4096) -> dict[str, Any]:
    """Read a PFS0 directory table and list file metadata; never extract file payloads."""
    file_size = path.stat().st_size
    with path.open("rb") as stream:
        header = stream.read(0x10)
        if len(header) != 0x10 or header[:4] != b"PFS0":
            raise ValueError("not a PFS0 container")
        file_count, string_size, _reserved = struct.unpack_from("<III", header, 4)
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
            offset, size, name_offset, _entry_reserved = struct.unpack_from(
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


def classify_asset_magic(prefix: bytes) -> str:
    """Identify a few common asset/container signatures without unpacking file contents."""
    signatures = (
        (b"UnityFS", "UnityFS"),
        (b"UnityRaw", "UnityRaw"),
        (b"UnityWeb", "UnityWeb"),
        (b"UnityArchive", "UnityArchive"),
        (b"OggS", "OggS"),
        (b"RIFF", "RIFF"),
        (b"fLaC", "FLAC"),
        (bytes((0x50, 0x4B, 0x03, 0x04)), "ZIP"),
    )
    for magic, label in signatures:
        if prefix.startswith(magic):
            return label
    return "other"


def scan_directory(root: Path, max_files: int, max_nso: int) -> dict[str, Any]:
    root = root.resolve()
    file_count = 0
    package_entries: list[dict[str, Any]] = []
    nso_paths: list[Path] = []
    magic_counts: Counter[str] = Counter()
    candidate_paths: list[str] = []
    main_npdm_path: Path | None = None
    exefs_found = root.name.lower() == "exefs"
    romfs_found = root.name.lower() == "romfs"
    romfs_file_count = 0
    romfs_total_bytes = 0
    romfs_extensions: Counter[str] = Counter()
    romfs_signatures: Counter[str] = Counter()
    romfs_signature_reads = 0
    romfs_samples: list[str] = []
    truncated = False

    for current, dirs, files in os.walk(root):
        dirs.sort()
        files.sort()
        current_rel = Path(current).relative_to(root)
        current_parts = [part.lower() for part in current_rel.parts]
        in_romfs = root.name.lower() == "romfs" or "romfs" in current_parts
        if "exefs" in current_parts or any(d.lower() == "exefs" for d in dirs):
            exefs_found = True
        if in_romfs or any(d.lower() == "romfs" for d in dirs):
            romfs_found = True
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
            if in_romfs:
                romfs_file_count += 1
                romfs_total_bytes += size
                romfs_extensions[suffix or "(no extension)"] += 1
                if len(romfs_samples) < 20:
                    romfs_samples.append(rel)
                if romfs_signature_reads < 32:
                    romfs_signature_reads += 1
                    signature = classify_asset_magic(read_prefix(path, 16))
                    if signature != "other":
                        romfs_signatures[signature] += 1
            if lower_name == "main.npdm":
                main_npdm_path = path

            if suffix in {".nsp", ".xci", ".nca", ".nsz", ".xcz"}:
                try:
                    kind = classify_magic(read_prefix(path))
                except OSError:
                    kind = "unreadable"
                package_entries.append({"path": rel, "size_bytes": size, "signature": kind})
                continue

            if lower_name in {"main", "sdk", "rtld"} or lower_name.startswith("subsdk") or suffix == ".nso":
                try:
                    if read_prefix(path, 4) == b"NSO0":
                        nso_paths.append(path)
                except OSError:
                    pass

            try:
                kind = classify_magic(read_prefix(path))
                if kind != "unknown":
                    magic_counts[kind] += 1
            except OSError:
                pass

            lowered = rel.lower()
            if (
                "/exefs/" in f"/{lowered}/"
                or "/romfs/" in f"/{lowered}/"
                or lower_name in {"main", "main.npdm", "sdk", "rtld"}
                or lower_name.startswith("subsdk")
            ):
                candidate_paths.append(rel)
        if truncated:
            break

    # Prioritize the normal main NSO name, then inspect other Switch modules.
    nso_paths.sort(key=lambda p: (0 if p.name.lower() == "main" else 1, str(p).lower()))
    nso_reports = [
        inspect_nso(path, path.relative_to(root).as_posix())
        for path in nso_paths[:max_nso]
    ]
    main_nso = next((x for x in nso_reports if Path(x["path"]).name.lower() == "main"), None)

    npdm_info = None
    if main_npdm_path and main_npdm_path.is_file():
        npdm_info = {
            "path": main_npdm_path.relative_to(root).as_posix(),
            "size_bytes": main_npdm_path.stat().st_size,
            "sha256": sha256_file(main_npdm_path),
            "header_hex": read_prefix(main_npdm_path, 32).hex(),
        }

    return {
        "input_directory": root.name,
        "file_count_seen": min(file_count, max_files),
        "scan_truncated": truncated,
        "package_archives": package_entries[:200],
        "package_archive_count": len(package_entries),
        "extracted_nso_count": len(nso_paths),
        "nso_modules_inspected": nso_reports,
        "nso_modules_omitted": max(0, len(nso_paths) - len(nso_reports)),
        "main_nso": main_nso,
        "main_npdm": npdm_info,
        "exefs_found": exefs_found,
        "romfs": {
            "found": romfs_found,
            "file_count": romfs_file_count,
            "total_bytes": romfs_total_bytes,
            "extension_counts": dict(sorted(romfs_extensions.items())),
            "recognized_signatures": dict(sorted(romfs_signatures.items())),
            "sample_paths": romfs_samples,
            "counts_are_partial": truncated,
        },
        "magic_counts_in_sampled_files": dict(magic_counts),
        "candidate_paths": candidate_paths[:200],
        "notes": [
            "This is a read-only metadata probe. It does not decrypt, unpack, patch or modify game files.",
            "NSO headers and imports are useful evidence, not proof that an existing Android runtime can boot the title.",
            "If the input contains only NSP/XCI/NCA package files, their contents are not inspected by this tool.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="an extracted game directory, or a single .nsp/.xci/.nso file")
    parser.add_argument("--json", dest="json_path", type=Path, help="write the JSON report to this path")
    parser.add_argument("--max-files", type=int, default=MAX_DEFAULT_FILES)
    parser.add_argument("--max-nso", type=int, default=32)
    args = parser.parse_args()

    if args.max_files < 1 or args.max_nso < 1:
        parser.error("--max-files and --max-nso must be positive")
    if not args.input.exists():
        parser.error(f"input does not exist: {args.input}")

    if args.input.is_file():
        report = inspect_single_file(args.input)
    elif args.input.is_dir():
        report = scan_directory(args.input, args.max_files, args.max_nso)
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
