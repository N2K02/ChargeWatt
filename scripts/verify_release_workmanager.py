#!/usr/bin/env python3
"""Fail CI if R8 removes the constructor used at WorkManager startup.

Inspects the actual minified release DEX, not just source or R8 rule text.
This guards the startup failure previously seen on Android:
  NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

DESCRIPTOR = "Landroidx/work/impl/WorkDatabase_Impl;"


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify_release_workmanager.py <release-unsigned.apk>", file=sys.stderr)
        return 2
    apk = Path(sys.argv[1])
    if not apk.is_file():
        print(f"Release APK missing: {apk}", file=sys.stderr)
        return 2

    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    candidates = sorted((sdk / "build-tools").glob("*/dexdump"))
    if not candidates:
        print(f"Android SDK dexdump not found under {sdk}", file=sys.stderr)
        return 2
    dexdump = candidates[-1]

    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as tmp:
        dexnames = sorted(name for name in archive.namelist()
                          if re.fullmatch(r"classes[0-9]*\.dex", name))
        if not dexnames:
            print("Release APK has no classes.dex", file=sys.stderr)
            return 1
        for dexname in dexnames:
            path = Path(tmp) / dexname
            path.write_bytes(archive.read(dexname))
            output = subprocess.run([str(dexdump), str(path)], capture_output=True,
                                    text=True, timeout=120, check=True).stdout
            # Extract only the single generated Room database class definition.
            match = re.search(r"Class descriptor\s*:\s*'" + re.escape(DESCRIPTOR) + r"'", output)
            if not match:
                continue
            rest = output[match.end():]
            next_class = re.search(r"(?m)^Class #[0-9]+", rest)
            body = rest[:next_class.start()] if next_class else rest
            constructor = re.search(
                r"name\s*:\s*'<init>'\s*\n\s*"
                r"type\s*:\s*'\(\)V'\s*\n\s*"
                r"access\s*:\s*[^\n]*\bPUBLIC\b", body)
            if constructor:
                print(f"PASS: public no-arg WorkDatabase_Impl constructor present in {dexname}")
                return 0
            print("FAIL: WorkDatabase_Impl exists, but public no-arg constructor "
                  "was stripped/changed by R8", file=sys.stderr)
            return 1

    print("FAIL: WorkDatabase_Impl was removed or renamed in minified Release APK",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
