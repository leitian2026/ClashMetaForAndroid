#!/usr/bin/env python3
"""Enforce monotonic line-count ceilings for custom Kotlin files and functions."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIMITS = ROOT / "scripts" / "size-limits.txt"
FILE_THRESHOLD = 800
FUNCTION_THRESHOLD = 150
MODIFIERS = (
    "public", "private", "protected", "internal", "override", "suspend",
    "inline", "tailrec", "operator", "infix", "open", "final", "external",
    "actual", "expect",
)
FUN_RE = re.compile(
    r"^\s*(?:(?:" + "|".join(MODIFIERS) + r")\s+)*fun\s+(?:<[^>]+>\s*)?([A-Za-z_][A-Za-z0-9_]*)"
)


def tracked_custom_kotlin_files() -> list[Path]:
    try:
        output = subprocess.check_output(
            ["git", "ls-files", "-z", "--", "*.kt"], cwd=ROOT
        )
        names = [name.decode() for name in output.split(b"\0") if name]
    except (OSError, subprocess.CalledProcessError):
        names = [
            path.relative_to(ROOT).as_posix()
            for path in ROOT.rglob("*.kt")
            if ".git" not in path.parts and "build" not in path.parts
        ]
    return sorted(
        ROOT / name for name in names
        if "/custom/" in ("/" + name.replace("\\", "/"))
        and (ROOT / name).is_file()
    )


def function_lengths(path: Path) -> dict[str, int]:
    lines = path.read_text(encoding="utf-8").splitlines()
    result: dict[str, int] = {}
    relative = path.relative_to(ROOT).as_posix()
    for index, line in enumerate(lines):
        match = FUN_RE.match(line)
        if not match:
            continue
        indent = len(line) - len(line.lstrip())
        end = index + 1
        while end < len(lines):
            candidate = lines[end]
            stripped = candidate.strip()
            if stripped and not stripped.startswith(("//", "*")) and not stripped.startswith("@"):
                candidate_indent = len(candidate) - len(candidate.lstrip())
                if candidate_indent <= indent:
                    break
            end += 1
        key = f"{relative}::{match.group(1)}"
        result[key] = max(result.get(key, 0), end - index)
    return result


def current_sizes() -> tuple[dict[str, int], dict[str, int]]:
    files: dict[str, int] = {}
    functions: dict[str, int] = {}
    for path in tracked_custom_kotlin_files():
        relative = path.relative_to(ROOT).as_posix()
        lines = path.read_text(encoding="utf-8").splitlines()
        files[relative] = len(lines)
        functions.update(function_lengths(path))
    return files, functions


def read_limits() -> dict[str, int]:
    limits: dict[str, int] = {}
    if not LIMITS.exists():
        return limits
    for number, raw in enumerate(LIMITS.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.rsplit(maxsplit=1)
        if len(fields) != 2 or not fields[1].isdigit():
            raise ValueError(f"{LIMITS.name}:{number}: expected '<path[::function]> <line-count>'")
        limits[fields[0]] = int(fields[1])
    return limits


def update_limits(files: dict[str, int], functions: dict[str, int]) -> int:
    entries = [
        (path, count) for path, count in files.items() if count > FILE_THRESHOLD
    ]
    entries += [
        (path, count) for path, count in functions.items() if count > FUNCTION_THRESHOLD
    ]
    entries.sort()
    LIMITS.write_text(
        "# Generated once with: python3 scripts/check-function-length.py --update\n"
        + "".join(f"{path} {count}\n" for path, count in entries),
        encoding="utf-8",
    )
    print(f"Generated {LIMITS.relative_to(ROOT)} ({len(entries)} entries):")
    for path, count in entries:
        print(f"{path} {count}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--update", action="store_true", help="generate the initial ceilings")
    args = parser.parse_args()
    files, functions = current_sizes()
    if args.update:
        if LIMITS.exists() and LIMITS.read_text(encoding="utf-8").strip():
            print("--update is only for initial generation; lower existing limits manually.", file=sys.stderr)
            return 2
        return update_limits(files, functions)

    try:
        limits = read_limits()
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2

    failed = False
    for path, count in files.items():
        limit = limits.get(path)
        if count > FILE_THRESHOLD and limit is None:
            print(f"ERROR: new custom Kotlin file exceeds {FILE_THRESHOLD} lines: {path} ({count})")
            failed = True
        elif limit is not None and count > limit:
            print(f"ERROR: custom Kotlin file grew: {path} ({count} > {limit})")
            failed = True

    for path, count in functions.items():
        limit = limits.get(path)
        if count > FUNCTION_THRESHOLD and limit is None:
            print(f"ERROR: new custom Kotlin function exceeds {FUNCTION_THRESHOLD} lines: {path} ({count})")
            failed = True
        elif limit is not None and count > limit:
            print(f"ERROR: custom Kotlin function grew: {path} ({count} > {limit})")
            failed = True

    if failed:
        return 1
    print(
        f"OK: checked {len(files)} custom Kotlin files and {len(functions)} functions; "
        f"{sum(1 for key in limits if '::' not in key)} file ceilings and "
        f"{sum(1 for key in limits if '::' in key)} function ceilings."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
