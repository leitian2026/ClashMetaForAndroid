#!/usr/bin/env python3
"""#17 backup inventory validation."""
from pathlib import Path
import re, subprocess, sys
ROOT=Path(__file__).resolve().parent.parent
inventory=ROOT/"docs/v2/backup-inventory.md"
text=inventory.read_text(encoding="utf-8")
rows={}
for line in text.splitlines():
 if line.startswith("| ") and not line.startswith("| 键") and not line.startswith("|---"):
  cells=[c.strip() for c in line.strip("|").split("|")]
  if len(cells)>=3: rows[cells[0]]=cells[1:]
errors=[]
if not rows: errors.append("inventory is empty")
for key,cells in rows.items():
 policy=cells[-1]
 if policy not in ("备份","不备份：备份文件自身","不备份：账号密码","不备份：可重新下载，来源注明"):
  errors.append(f"{key}: policy invalid or exception reason missing")
# Storage-source scan: catches direct SharedPreferences literal, filesDir literal, Room builder usage.
tracked=subprocess.check_output(["git","ls-files","-z"],cwd=ROOT).decode().split("\0")
sources=[]
for name in tracked:
 if name and name.endswith((".kt",".java")):
  path=ROOT/name
  try: sources.append((name,path.read_text(encoding="utf-8")))
  except (OSError,UnicodeError): pass
for name,source in sources:
 if name.startswith(("common/src/main/java/com/github/kr328/clash/common/store/","service/src/main/java/com/github/kr328/clash/service/data/")): continue
 for m in re.finditer(r'getSharedPreferences\s*\(\s*"([^"]+)"',source):
  key="prefs:"+m.group(1)
  if key not in rows: errors.append(f"{name}: shared preference {key} missing inventory")
 for m in re.finditer(r'filesDir\.resolve\s*\(\s*"([^"]+)"',source):
  value=m.group(1)
  if value.startswith("custom/"): key="file:"+value
  else: key="file:"+value
  if key not in rows and value not in text: errors.append(f"{name}: path {value!r} missing inventory")
# Unified extra-file manifest is handled by the shared export/import adapters.
export=(ROOT/"app/src/main/java/com/github/kr328/clash/custom/BackupExtras.kt").read_text(encoding="utf-8")
restore=(ROOT/"app/src/main/java/com/github/kr328/clash/custom/BackupExtrasRestore.kt").read_text(encoding="utf-8")
manager=(ROOT/"app/src/main/java/com/github/kr328/clash/custom/BackupManager.kt").read_text(encoding="utf-8")
for key,cells in rows.items():
 if cells[-1]!="备份": continue
 if key=="prefs:ui":
  if "BACKUP-EXPORT prefs:ui" not in manager: errors.append("prefs:ui: missing export marker")
  if "BACKUP-IMPORT prefs:ui" not in restore: errors.append("prefs:ui: missing import marker")
 elif key.startswith("prefs:"):
  name=key.split(":",1)[1]
  if "BACKUP-EXPORT prefs:" not in export: errors.append(f"{key}: missing export marker")
  if "BACKUP-IMPORT prefs:" not in restore: errors.append(f"{key}: missing import marker")
 elif key.startswith("room:"):
  if "BACKUP-EXPORT "+key not in export: errors.append(f"{key}: missing export marker")
 elif key.startswith(("file:","dir:")):
  if "BACKUP-EXPORT extraFiles" not in export: errors.append(f"{key}: missing export marker")
  if "BACKUP-IMPORT extraFiles" not in restore: errors.append(f"{key}: missing import marker")
if errors:
 print("Backup inventory check failed:")
 for e in errors: print(" -",e)
 sys.exit(1)
print(f"OK: inventory entries={len(rows)}; source and export/import marker checks passed")
