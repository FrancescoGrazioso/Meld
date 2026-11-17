#!/usr/bin/env python3
"""
Generate installer icon files (logo.png, logo.ico, logo.icns) from Meld's
Default app icon.

Usage:
  python3 scripts/gen_icons.py

Inputs  (from assets/app-icons/meld/):
  desktop.png        — 1024×1024 rounded square                (Linux, Windows)
  desktop-macos.png  — the same on the 824-in-1024 macOS grid  (macOS)

Outputs (written to desktopApp/src/desktopMain/resources/):
  logo.png  — 512×512                             (Linux .deb / .rpm / AppImage)
  logo.ico  — multi-size ICO                      (Windows .msi)
  logo.icns — macOS icon bundle via iconutil       (macOS .dmg, macOS only)
"""

import os
import sys
import platform
import subprocess
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).parent.parent
RESOURCES = REPO_ROOT / "desktopApp" / "src" / "desktopMain" / "resources"

MASTERS = REPO_ROOT / "assets" / "app-icons" / "meld"

ICO_SIZES = [16, 32, 48, 64, 128, 256]


def make_icon(canvas: int, master: str = "desktop.png") -> "Image.Image":
    """The master artwork at the given size."""
    from PIL import Image

    return Image.open(MASTERS / master).convert("RGBA").resize((canvas, canvas), Image.LANCZOS)


def write_linux_png():
    img = make_icon(512)
    out = RESOURCES / "logo.png"
    img.save(str(out), "PNG")
    print(f"  logo.png  -> {out}")


def write_windows_ico():
    base = make_icon(256)
    out = RESOURCES / "logo.ico"
    base.save(
        str(out),
        format="ICO",
        sizes=[(s, s) for s in ICO_SIZES],
    )
    print(f"  logo.ico  -> {out}")


def write_macos_icns():
    """Requires macOS + iconutil (ships with Xcode Command Line Tools)."""
    with tempfile.TemporaryDirectory() as td:
        iconset = Path(td) / "icon.iconset"
        iconset.mkdir()

        # macOS standard iconset requires both 1× and 2× variants
        for base_size in [16, 32, 128, 256, 512]:
            make_icon(base_size, "desktop-macos.png").save(
                str(iconset / f"icon_{base_size}x{base_size}.png")
            )
            make_icon(base_size * 2, "desktop-macos.png").save(
                str(iconset / f"icon_{base_size}x{base_size}@2x.png")
            )

        out = RESOURCES / "logo.icns"
        result = subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(out)],
            capture_output=True,
        )
        if result.returncode != 0:
            print(
                f"  iconutil failed: {result.stderr.decode().strip()}",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"  logo.icns -> {out}")



def main():
    try:
        from PIL import Image  # noqa: F401
    except ImportError as error:
        raise SystemExit("Pillow is required; install the pinned icon-generation dependency first") from error

    os_name = platform.system()
    print(f"Generating installer icons (platform={os_name})...")

    write_linux_png()

    if os_name == "Windows":
        write_windows_ico()
    elif os_name == "Darwin":
        write_macos_icns()

    print("Done.")


if __name__ == "__main__":
    main()
