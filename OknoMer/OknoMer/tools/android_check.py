"""Run the two Android instrumentation phases on a connected device or emulator."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

PACKAGE = "org.student.oknomer.debug"
RUNNER = PACKAGE + ".test/org.student.oknomer.Verify"


def adb_command():
    adb = shutil.which("adb")
    if adb:
        return adb
    sdk = os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT")
    if sdk:
        binary = Path(sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
        if binary.is_file():
            return str(binary)
    raise SystemExit("Укажите ANDROID_HOME или добавьте adb в PATH.")


def main():
    adb = adb_command()
    for args in ([], ["-e", "phase", "reopen"]):
        if args:
            subprocess.run([adb, "shell", "am", "force-stop", PACKAGE], check=True)
        result = subprocess.run(
            [adb, "shell", "am", "instrument", "-w", *args, RUNNER],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8",
            errors="replace", timeout=600,
        )
        print(result.stdout, flush=True)
        if result.returncode or "ALL PASSED" not in result.stdout.splitlines():
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
