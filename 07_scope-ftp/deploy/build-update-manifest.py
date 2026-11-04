#!/usr/bin/env python3
"""从 Tauri updater 产物生成 GitHub Release 使用的 latest.json。"""

from __future__ import annotations

import argparse
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

PLATFORMS = {
    "darwin-aarch64": ("ScopeFTP-macos-arm64", "aarch64", "app.tar.gz"),
    "darwin-x86_64": ("ScopeFTP-macos-x64", "x64", "app.tar.gz"),
    # Windows 的 Tauri updater 产物就是 NSIS 安装包本身，签名文件是同名 .exe.sig。
    "windows-x86_64": ("ScopeFTP-client-windows-x64", "x64", "setup.exe"),
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--out-dir", required=True, type=Path)
    args = parser.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    platforms: dict[str, dict[str, str]] = {}
    for key, (artifact_name, arch, suffix) in PLATFORMS.items():
        folder = args.artifacts / artifact_name
        matches = sorted(folder.rglob(f"*{suffix}"))
        if len(matches) != 1:
            raise SystemExit(
                f"{key}: expected exactly one *{suffix} under {folder}, got {matches}"
            )
        archive = matches[0]
        signature = archive.with_name(archive.name + ".sig")
        if not signature.is_file():
            raise SystemExit(f"{key}: missing signature file {signature}")

        if key == "windows-x86_64":
            filename = f"ScopeFTP_{args.version}_{arch}-setup.exe"
        else:
            filename = f"ScopeFTP_{args.version}_{arch}.{suffix}"
        shutil.copy2(archive, args.out_dir / filename)
        shutil.copy2(signature, args.out_dir / f"{filename}.sig")
        platforms[key] = {
            "signature": signature.read_text(encoding="utf-8").strip(),
            "url": (
                "https://github.com/fengwenshan/java-demo/releases/download/"
                f"{args.tag}/{filename}"
            ),
        }

    manifest = {
        "version": args.version,
        "notes": f"ScopeFTP {args.tag}",
        "pub_date": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "platforms": platforms,
    }
    latest = args.out_dir.parent / "latest.json"
    latest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
