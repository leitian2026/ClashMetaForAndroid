#!/usr/bin/env python3
"""#17 backup inventory validation.

Usage:
  check-backup.py              check the repository this script lives in
  check-backup.py --self-test  prove the checker catches each kind of mistake

What is checked (errors are collected, then printed together):
  1. docs/v2/backup-inventory.md: every table row has exactly 3 cells, a known
     key prefix, no duplicate key, a valid policy (and a source for re-downloadable data).
  2. Source scan: every SharedPreferences name, filesDir path (resolve/File/AtomicFile),
     Room @Entity table, DataStore and SQLite database found in code must be registered.
  3. Reverse scan: every registered key must still exist in code.
  4. Markers: for each "备份" key, the matching `BACKUP-EXPORT` / `BACKUP-IMPORT`
     marker must exist in a Backup*.kt file, sit next to real code for it, and every
     marker must point at a registered "备份" key.
  5. A literal backslash-n left inside a line comment (a damaged file write) is an error.
"""
from pathlib import Path
import fnmatch
import os
import re
import shutil
import subprocess
import sys
import tempfile

INVENTORY = "docs/v2/backup-inventory.md"
POLICY_BACKUP = "备份"
POLICIES = (
    POLICY_BACKUP,
    "不备份：备份文件自身",
    "不备份：账号密码",
    "不备份：可重新下载，来源注明",
)
KEY_RE = re.compile(r"^(prefs|room|dir|file|datastore|db):\S+$")
SKIP_PARTS = ("/build/", "/.git/", "/src/test/", "/src/androidTest/", "/core/src/foss/")
# Core stores read their own names/paths; Room entities and migrations live here too.
STORE_DIRS = (
    "common/src/main/java/com/github/kr328/clash/common/store/",
    "service/src/main/java/com/github/kr328/clash/service/data/",
)
# Registered keys that are not a literal in code, with the code token that proves them.
EVIDENCE = {"prefs:service": ["createSharedPreferencesFromContext"], "file:backup/archive": None}

TOKEN = re.compile(
    r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])\'|//[^\n]*|/\*[\s\S]*?\*/'
)
STRING = re.compile(r'"((?:\\.|[^"\\\n])*)"')
CONST = re.compile(r'\bval\s+([A-Za-z_]\w*)\s*=\s*"([^"$\\]*)"')
PREFS_CALL = re.compile(r'getSharedPreferences\s*\(\s*(?:"([^"]+)"|([A-Za-z_][\w.]*))')
FILES_LITERAL = re.compile(
    r'filesDir\s*(?:\.resolve\s*\(|,)\s*"([^"]*)"((?:\s*\)\s*\.resolve\s*\(\s*"[^"]*")*)'
)
FILES_CONST = re.compile(r"filesDir\s*,\s*([A-Z][A-Z0-9_]*)\b")
CHAIN_PART = re.compile(r'"([^"]*)"')
ATOMIC_CALL = re.compile(r"AtomicFile\s*\(")
ENTITY = re.compile(r"@Entity\s*\(([^)]*)\)")
TABLE_NAME = re.compile(r'tableName\s*=\s*"([^"]+)"')
DATASTORE = re.compile(r'(?:preferencesDataStore|dataStore)\s*\(\s*(?:\w+\s*=\s*)?"([^"]+)"')
SQLITE_CALLS = (
    re.compile(r'openOrCreateDatabase\s*\(\s*"([^"]+)"'),
    re.compile(r'SQLiteOpenHelper\s*\(\s*[^,()]+,\s*"([^"]+)"'),
)
MARKER = re.compile(r"BACKUP-(EXPORT|IMPORT)\s+((?:prefs|room|dir|file|datastore|db):[^\s)]+)")
DYNAMIC_MARKER = re.compile(r"BACKUP-(EXPORT|IMPORT)\s+prefs:\s+\(dynamic")
GENERIC_MARKER = re.compile(r"BACKUP-(EXPORT|IMPORT)\s+extraFiles")
LITERAL_NEWLINE = re.compile(r"//[^\n]*\\n[ \t]{2,}\S")
NEXT_LINES = 8


def blank(match):
    text = match.group(0)
    if text[0] in "\"'":
        return text
    return re.sub(r"[^\n]", " ", text)


def strip_comments(text):
    return TOKEN.sub(blank, text)


def list_files(root):
    if (root / ".git").exists():
        raw = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode()
        return [n for n in raw.split("\0") if n]
    return [str(p.relative_to(root)).replace(os.sep, "/") for p in root.rglob("*") if p.is_file()]


def load_sources(root):
    sources = {}
    for name in list_files(root):
        if not name.endswith((".kt", ".java")):
            continue
        if any(part in "/" + name for part in SKIP_PARTS):
            continue
        try:
            sources[name] = (root / name).read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            continue
    return sources


def parse_inventory(text):
    rows, errors = {}, []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.startswith("|") or line.startswith("| 键") or line.startswith("|---"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 3:
            errors.append(f"inventory line {number}: {len(cells)} cells, expected 3 (two rows merged on one line?)")
            continue
        key, where, policy = cells
        if not KEY_RE.match(key):
            errors.append(f"inventory line {number}: bad key {key!r}")
            continue
        if key in rows:
            errors.append(f"inventory line {number}: duplicate key {key}")
        if policy not in POLICIES:
            errors.append(f"{key}: policy {policy!r} invalid or exception reason missing")
        elif "可重新下载" in policy and "来源" not in where and "http" not in where:
            errors.append(f"{key}: re-downloadable data must name its source in the second column")
        rows[key] = policy
    if not rows:
        errors.append("inventory is empty")
    return rows, errors


def template_glob(value):
    value = re.sub(r"\$\{[^}]*\}|\$\w+", "*", value)
    return re.sub(r"\*+", "*", value)


def path_matches(found, registered):
    registered = registered[:-2] if registered.endswith("/*") else registered.rstrip("/")
    found = found.rstrip("/")
    return (
        fnmatch.fnmatchcase(found, registered)
        or found == registered
        or found.startswith(registered + "/")
    )


class Scan:
    def __init__(self, sources):
        self.code = {n: strip_comments(t) for n, t in sources.items()}
        self.consts = {}  # simple name -> every value it has anywhere
        self.file_consts = {}  # file -> {simple name: value}
        for file, text in self.code.items():
            self.file_consts[file] = dict(CONST.findall(text))
            for name, value in CONST.findall(text):
                self.consts.setdefault(name, set()).add(value)
        self.literals = "\0".join(m for t in self.code.values() for m in STRING.findall(t))
        self.found = []  # (key, file, detail)
        self.errors = []

    def add(self, key, name, detail):
        self.found.append((key, name, detail))

    def resolve(self, file, ident):
        simple = ident.split(".")[-1]
        if simple in self.file_consts[file]:
            return {self.file_consts[file][simple]}
        values = self.consts.get(simple, set())
        return values if len(values) == 1 else set()

    def scan_prefs(self, name, text):
        for match in PREFS_CALL.finditer(text):
            literal, ident = match.groups()
            if literal:
                self.add("prefs:" + literal, name, "SharedPreferences")
                continue
            values = self.resolve(name, ident)
            if values:
                for value in values:
                    self.add("prefs:" + value, name, "SharedPreferences")
            elif not (re.match(r"^[a-z]\w*$", ident) and name.rsplit("/", 1)[-1].startswith("Backup")):
                self.errors.append(f"{name}: SharedPreferences name {ident!r} cannot be resolved; use a literal or a const val")

    def scan_files(self, name, text):
        for match in FILES_LITERAL.finditer(text):
            parts = [match.group(1)] + CHAIN_PART.findall(match.group(2))
            self.add("path:" + template_glob("/".join(p.strip("/") for p in parts)), name, "filesDir path")
        for match in FILES_CONST.finditer(text):
            values = self.resolve(name, match.group(1))
            if not values:
                self.errors.append(f"{name}: filesDir constant {match.group(1)} cannot be resolved; use a literal or a const val")
            for value in values:
                self.add("path:" + template_glob(value), name, "filesDir path")
        for match in ATOMIC_CALL.finditer(text):
            around = text[max(0, match.start() - 300):match.end() + 260]
            if not (FILES_LITERAL.search(around) or FILES_CONST.search(around)):
                self.errors.append(f"{name}: AtomicFile target cannot be resolved; use filesDir with a string literal")

    def scan_everything(self, name, text):
        for match in ENTITY.finditer(text):
            table = TABLE_NAME.search(match.group(1))
            if table:
                self.add("room:" + table.group(1), name, "Room entity")
            else:
                self.errors.append(f"{name}: @Entity without tableName cannot be matched to the inventory")
        for value in DATASTORE.findall(text):
            self.add("datastore:" + value, name, "DataStore")
        for pattern in SQLITE_CALLS:
            for value in pattern.findall(text):
                self.add("db:" + value, name, "SQLite database")

    def run(self):
        for name, text in self.code.items():
            self.scan_everything(name, text)
            if not name.startswith(STORE_DIRS):
                self.scan_prefs(name, text)
                self.scan_files(name, text)
        return self


def check_registration(scan, rows):
    errors = list(scan.errors)
    paths = [(k, k.split(":", 1)[1]) for k in rows if k.startswith(("dir:", "file:"))]
    for key, name, detail in scan.found:
        if key.startswith("path:"):
            value = key[5:]
            if not any(path_matches(value, p) for _, p in paths):
                errors.append(f"{name}: {detail} {value!r} missing inventory")
        elif key not in rows:
            errors.append(f"{name}: {detail} {key} missing inventory")
    return errors


def static_parts(path):
    path = path[:-2] if path.endswith("/*") else path
    if "*" not in path:
        return [path]
    return [re.split(r"\*", seg)[0] for seg in path.split("/") if re.split(r"\*", seg)[0]]


def check_reverse(scan, rows):
    errors = []
    for key in rows:
        kind, rest = key.split(":", 1)
        if key in EVIDENCE:
            tokens = EVIDENCE[key]
            if tokens and not all(t in "".join(scan.code.values()) for t in tokens):
                errors.append(f"{key}: registered but code evidence {tokens} is gone")
        elif kind == "prefs":
            if f'"{rest}"' not in "".join(scan.code.values()):
                errors.append(f"{key}: registered but no code uses this SharedPreferences name any more")
        elif kind in ("dir", "file"):
            scanned = any(k[5:] == rest or path_matches(k[5:], rest) for k, _, _ in scan.found if k.startswith("path:"))
            missing = [p for p in static_parts(rest) if p not in scan.literals]
            if missing and not scanned:
                errors.append(f"{key}: registered but code no longer contains {missing}")
        elif key not in {k for k, _, _ in scan.found}:
            errors.append(f"{key}: registered but no {kind} is declared in code any more")
    return errors


def collect_markers(sources):
    markers, dynamic, generic, errors = [], set(), set(), []
    for name, text in sources.items():
        if not name.rsplit("/", 1)[-1].startswith("Backup"):
            continue
        lines = text.splitlines()
        for number, line in enumerate(lines):
            if LITERAL_NEWLINE.search(line):
                errors.append(f"{name}:{number + 1}: literal backslash-n inside a comment (damaged line break)")
            for match in DYNAMIC_MARKER.finditer(line):
                dynamic.add(match.group(1))
            for match in GENERIC_MARKER.finditer(line):
                generic.add(match.group(1))
            for match in MARKER.finditer(line):
                key = match.group(2)
                key = key[:-2] if key.endswith("/*") else key
                following = [l for l in lines[number + 1:number + 1 + NEXT_LINES] if "BACKUP-" not in l]
                nearby = "\n".join([line.split("//")[0]] + following)
                markers.append((match.group(1), key, name, nearby))
    return markers, dynamic, generic, errors


def pref_names(sources):
    names = set()
    for name, text in sources.items():
        if name.rsplit("/", 1)[-1].startswith("Backup"):
            for block in re.findall(r"PREF_NAMES\s*=\s*listOf\(([^)]*)\)", text):
                names.update(STRING.findall(block))
    return names


def marker_evidence(key, nearby):
    kind, rest = key.split(":", 1)
    if kind == "prefs":
        haystack = nearby.lower().replace("_", "")
        names = {rest, rest[7:] if rest.startswith("custom_") else rest}
        return any(n.lower().replace("_", "") in haystack for n in names)
    if kind in ("dir", "file"):
        wanted = static_parts(rest)[-1:] + [rest.rsplit("/", 1)[-1] + "Dir"]
        return any(w and w in nearby for w in wanted)
    return True


def check_markers(sources, rows):
    markers, dynamic, generic, errors = collect_markers(sources)
    names = pref_names(sources)
    for direction, key, name, _ in markers:
        if key not in rows:
            errors.append(f"{name}: BACKUP-{direction} {key} is not in the inventory")
        elif rows[key] != POLICY_BACKUP:
            errors.append(f"{name}: BACKUP-{direction} {key} but the inventory says it is not backed up")
    for key, policy in rows.items():
        if policy != POLICY_BACKUP:
            continue
        kind, rest = key.split(":", 1)
        for direction in ("EXPORT", "IMPORT"):
            exact = [m for m in markers if m[0] == direction and m[1] == key]
            if exact:
                if direction == "EXPORT" or kind == "prefs":
                    if not any(marker_evidence(key, m[3]) for m in exact):
                        errors.append(f"{key}: BACKUP-{direction} marker is not next to code for it")
            elif kind in ("dir", "file") and direction == "IMPORT" and direction in generic:
                continue  # restored by the shared extraFiles importer
            elif kind == "prefs" and direction in dynamic and rest in names:
                continue
            else:
                errors.append(f"{key}: missing {direction.lower()} marker")
    return errors


def literal_newline_errors(sources):
    return [
        f"{name}:{number}: literal backslash-n inside a comment (damaged line break)"
        for name, text in sources.items()
        if not name.rsplit("/", 1)[-1].startswith("Backup")
        for number, line in enumerate(text.splitlines(), 1)
        if LITERAL_NEWLINE.search(line)
    ]


def run_checks(root):
    root = Path(root)
    rows, errors = parse_inventory((root / INVENTORY).read_text(encoding="utf-8"))
    sources = load_sources(root)
    scan = Scan(sources).run()
    errors += check_registration(scan, rows)
    errors += check_reverse(scan, rows)
    errors += check_markers(sources, rows)
    errors += literal_newline_errors(sources)
    return rows, sorted(set(errors))


# ---------------------------------------------------------------- self-test

INV_HEADER = "# Backup inventory\n| 键 | 持久化位置/来源 | 策略 |\n|---|---|---|\n"
FIXTURE_INVENTORY = INV_HEADER + """\
| prefs:ui | ui prefs | 备份 |
| prefs:extra | extra prefs | 备份 |
| prefs:secret | secret prefs | 不备份：账号密码 |
| room:things | Room things | 备份 |
| dir:custom/crash | filesDir/custom/crash | 备份 |
| file:custom_pair_*.json | per-profile files | 备份 |
| dir:cache_dl | 来源：https://example.org | 不备份：可重新下载，来源注明 |
"""
FIXTURE_FILES = {
    "app/Backup1.kt": """\
object BackupManager {
    fun write() {
        // BACKUP-EXPORT prefs:ui
        put("ui", dump(context, "ui"))
        // BACKUP-EXPORT room:things
        // BACKUP-EXPORT extraFiles (manifest path list)
        // BACKUP-EXPORT dir:custom/crash/*
        add(context.filesDir.resolve("custom/crash"))
        // BACKUP-EXPORT file:custom_pair_*.json
        context.filesDir.listFiles { f -> f.name.startsWith("custom_pair_") }
        PREF_NAMES.forEach { name ->
            // BACKUP-EXPORT prefs: (dynamic group name)
            dump(context.getSharedPreferences(name, 0))
        }
    }
    private val PREF_NAMES = listOf(
        "extra",
    )
    fun read() {
        // BACKUP-IMPORT prefs:ui
        manifest.optJSONObject("ui")
        // BACKUP-IMPORT room:things
        // BACKUP-IMPORT extraFiles
        // BACKUP-IMPORT prefs: (dynamic group name)
        restore(context.getSharedPreferences(name, 0))
    }
}
""",
    "app/Stores.kt": """\
class Stores {
    val a = context.getSharedPreferences("ui", 0)
    val b = context.getSharedPreferences(SECRET, 0)
    val c = context.filesDir.resolve("cache_dl")
    val d = AtomicFile(File(context.filesDir, "custom_pair_$id.json"))
    val e = context.filesDir.resolve("custom").resolve("crash")
    companion object { private const val SECRET = "secret" }
}
""",
    "app/Same1.kt": 'object A { private const val FILE_NAME = "extra"; val p = x.getSharedPreferences(FILE_NAME, 0) }',
    "app/Same2.kt": 'object B { private const val FILE_NAME = "ui"; val p = x.getSharedPreferences(FILE_NAME, 0) }',
    "service/src/main/java/com/github/kr328/clash/service/data/Thing.kt": """\
@Entity(tableName = "things", primaryKeys = ["id"])
class Thing
""",
}


def build_fixture(root, inventory=FIXTURE_INVENTORY, files=None):
    (root / INVENTORY).parent.mkdir(parents=True, exist_ok=True)
    (root / INVENTORY).write_text(inventory, encoding="utf-8")
    for name, text in (files or FIXTURE_FILES).items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


def self_test_cases():
    files = FIXTURE_FILES
    b1, stores = files["app/Backup1.kt"], files["app/Stores.kt"]
    extra = lambda name, text: dict(files, **{name: text})
    inv = FIXTURE_INVENTORY
    return [
        ("new prefs literal", dict(files=extra("app/N.kt", 'x.getSharedPreferences("brand_new", 0)')), "prefs:brand_new"),
        ("new prefs const", dict(files=extra("app/N.kt", 'const val Q = "via_const"\nx.getSharedPreferences(Q, 0)')), "prefs:via_const"),
        ("prefs name unresolvable", dict(files=extra("app/N.kt", "x.getSharedPreferences(dynamicName, 0)")), "cannot be resolved"),
        ("new filesDir.resolve", dict(files=extra("app/N.kt", 'x.filesDir.resolve("custom/newdata")')), "custom/newdata"),
        ("chained resolve", dict(files=extra("app/N.kt", 'x.filesDir.resolve("custom").resolve("brand")')), "custom/brand"),
        ("filesDir with const", dict(files=extra("app/N.kt", 'object Z { const val FILE_NAME = "z_state.json"; val f = File(x.filesDir, FILE_NAME) }')), "z_state.json"),
        ("filesDir const unresolvable", dict(files=extra("app/N.kt", "val f = File(x.filesDir, FILE_NAME)")), "cannot be resolved"),
        ("new File(filesDir, ..)", dict(files=extra("app/N.kt", 'File(x.applicationContext.filesDir, "other.json")')), "other.json"),
        ("new AtomicFile", dict(files=extra("app/N.kt", 'AtomicFile(File(context.filesDir, "state_new.json"))')), "state_new.json"),
        ("AtomicFile unresolvable", dict(files=extra("app/N.kt", "AtomicFile(someFile)")), "AtomicFile target"),
        ("new Room entity", dict(files=extra("service/src/main/java/com/github/kr328/clash/service/data/N.kt", '@Entity(tableName = "gadgets")\nclass G')), "room:gadgets"),
        ("new DataStore", dict(files=extra("app/N.kt", 'val Context.ds by preferencesDataStore(name = "settings_ds")')), "datastore:settings_ds"),
        ("new SQLite db", dict(files=extra("app/N.kt", 'ctx.openOrCreateDatabase("mydb", 0, null)')), "db:mydb"),
        ("registered but code gone (dir)", dict(inventory=inv + "| dir:custom/gone | gone | 备份 |\n"), "custom/gone"),
        ("registered but code gone (prefs)", dict(inventory=inv + "| prefs:ghost | gone | 不备份：账号密码 |\n"), "prefs:ghost"),
        ("registered but code gone (room)", dict(inventory=inv + "| room:ghosts | gone | 备份 |\n"), "room:ghosts"),
        ("merged inventory line", dict(inventory=inv.replace("| 备份 |\n| prefs:extra", "| 备份| prefs:extra")), "cells, expected 3"),
        ("duplicate key", dict(inventory=inv + "| prefs:ui | again | 备份 |\n"), "duplicate key prefs:ui"),
        ("bad policy", dict(inventory=inv.replace("不备份：账号密码", "不备份")), "policy"),
        ("redownload without source", dict(inventory=inv.replace("来源：https://example.org", "cache")), "must name its source"),
        ("missing export marker", dict(files=extra("app/Backup1.kt", b1.replace("// BACKUP-EXPORT room:things\n", ""))), "room:things: missing export"),
        ("missing import marker", dict(files=extra("app/Backup1.kt", b1.replace("        // BACKUP-IMPORT prefs:ui\n", ""))), "prefs:ui: missing import"),
        ("code next to marker deleted", dict(files=extra("app/Backup1.kt", b1.replace('        add(context.filesDir.resolve("custom/crash"))\n', ""))), "not next to code"),
        ("storage code deleted everywhere", dict(files=dict(extra("app/Backup1.kt", b1.replace('        add(context.filesDir.resolve("custom/crash"))\n', "")), **{"app/Stores.kt": stores.replace('    val e = context.filesDir.resolve("custom").resolve("crash")\n', "")})), "registered but code no longer contains"),
        ("dangling marker", dict(files=dict(extra("app/Backup1.kt", b1.replace('        add(context.filesDir.resolve("custom/crash"))\n', "        add(somethingElse)\n")), **{"app/Other.kt": 'x.filesDir.resolve("custom/crash")'})), "not next to code"),
        ("dynamic prefs not in list", dict(files=extra("app/Backup1.kt", b1.replace('"extra",', ""))), "prefs:extra: missing export"),
        ("marker for unregistered key", dict(files=extra("app/Backup1.kt", b1.replace("BACKUP-EXPORT room:things", "BACKUP-EXPORT room:nothing"))), "room:nothing is not in the inventory"),
        ("marker on non-backup key", dict(files=extra("app/Backup1.kt", b1 + "// BACKUP-EXPORT prefs:secret\n")), "not backed up"),
        ("missing extraFiles import marker", dict(files=extra("app/Backup1.kt", b1.replace("        // BACKUP-IMPORT extraFiles\n", ""))), "dir:custom/crash: missing import marker"),
        ("literal newline in Backup file", dict(files=extra("app/Backup1.kt", b1.replace('// BACKUP-EXPORT prefs:ui\n        put', '// BACKUP-EXPORT prefs:ui\\n        put'))), "literal backslash-n"),
        ("literal newline in other file", dict(files=extra("app/N.kt", "// note\\n        val z = 1")), "literal backslash-n"),
    ]


def trailing_variants():
    """A marker may sit at the end of its code line (keeps big functions from growing)."""
    b1 = FIXTURE_FILES["app/Backup1.kt"]
    head = '        // BACKUP-EXPORT dir:custom/crash/*\n        add(context.filesDir.resolve("custom/crash"))\n'
    assert head in b1
    good = b1.replace(head, '        add(context.filesDir.resolve("custom/crash")) // BACKUP-EXPORT dir:custom/crash/*\n')
    bad = b1.replace(head, '        add(somethingElse) // BACKUP-EXPORT dir:custom/crash/*\n')
    return good, bad


def self_test():
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        build_fixture(root)
        _, base = run_checks(root)
        if base:
            print("self-test FAILED: baseline fixture should pass, got:")
            for line in base:
                print("  -", line)
            return 1
        good, bad = trailing_variants()
        build_fixture(root / "good", files=dict(FIXTURE_FILES, **{"app/Backup1.kt": good}))
        _, errors = run_checks(root / "good")
        if errors:
            failures += 1
            print(f"  FAIL trailing marker next to its code should pass, got {errors}")
        else:
            print("  ok   trailing marker next to its code")
        stray = {"app/Backup1.kt": bad, "app/Other.kt": 'x.filesDir.resolve("custom/crash")'}
        build_fixture(root / "bad", files=dict(FIXTURE_FILES, **stray))
        _, errors = run_checks(root / "bad")
        if any("not next to code" in e for e in errors):
            print("  ok   trailing marker without its code")
        else:
            failures += 1
            print(f"  FAIL trailing marker without its code not detected, got {errors}")
        for title, kwargs, expect in self_test_cases():
            case = root / "case"
            shutil.rmtree(case, ignore_errors=True)
            build_fixture(case, **kwargs)
            _, errors = run_checks(case)
            if any(expect in e for e in errors):
                print(f"  ok   {title}")
            else:
                failures += 1
                print(f"  FAIL {title}: expected an error containing {expect!r}, got {errors}")
    if failures:
        print(f"self-test FAILED: {failures} case(s) not detected")
        return 1
    print(f"self-test OK: baseline passes, {len(self_test_cases()) + 1} mistakes detected")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    root = Path(__file__).resolve().parent.parent
    rows, errors = run_checks(root)
    if errors:
        print("Backup inventory check failed:")
        for e in errors:
            print(" -", e)
        return 1
    print(f"OK: inventory entries={len(rows)}; registration, reverse, marker checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
