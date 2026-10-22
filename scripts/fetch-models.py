#!/usr/bin/env python3
"""Download and verify Adobe TrustMark Q ONNX models.

Usage:
  python scripts/fetch-models.py                 # -> ./models
  python scripts/fetch-models.py --to-assets     # -> app/src/main/assets/models
  python scripts/fetch-models.py --verify-only   # verify existing files

The model files are MIT licensed by Adobe but are not stored in the TrustMark
git repository. This script pins the exact SHA-256 digests used by the app.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import sys
import urllib.request
from pathlib import Path

BASE_URL = "https://cai-watermark.adobe.net/watermarking/trustmark-models"
ROOT = Path(__file__).resolve().parent.parent

MODELS = {
    "encoder_Q.onnx": (
        17_312_208,
        "19b3d1b25836130ffd78775a8f61539f993375d1823ef0e59ba5b8dffb4f892d",
    ),
    "decoder_Q.onnx": (
        47_401_222,
        "ee3268f057c9dabef680e169302f5973d0589feea86189ed229a896cc3aa88df",
    ),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: Path, expected_size: int, expected_hash: str) -> bool:
    if not path.is_file():
        print(f"MISSING {path}")
        return False
    if path.stat().st_size != expected_size:
        print(f"BAD SIZE {path}: {path.stat().st_size} != {expected_size}")
        return False
    actual = sha256(path)
    if actual != expected_hash:
        print(f"BAD SHA256 {path}: {actual}")
        return False
    print(f"OK {path.name} ({expected_size / 1024 / 1024:.1f} MB)")
    return True


def download(target: Path, expected_size: int, expected_hash: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    part.unlink(missing_ok=True)
    url = f"{BASE_URL}/{target.name}"
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "TrustMarkTool-Android/1.0", "Accept-Encoding": "identity"},
    )
    print(f"GET {url}")
    with urllib.request.urlopen(request, timeout=120) as response, part.open("wb") as output:
        total = int(response.headers.get("Content-Length") or expected_size)
        done = 0
        next_report = 4 * 1024 * 1024
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            output.write(chunk)
            done += len(chunk)
            if done >= next_report or done == total:
                print(f"  {done / 1024 / 1024:5.1f} / {total / 1024 / 1024:.1f} MB", flush=True)
                next_report += 4 * 1024 * 1024

    actual_size = part.stat().st_size
    actual_hash = sha256(part)
    if actual_size != expected_size:
        part.unlink(missing_ok=True)
        raise RuntimeError(f"size mismatch: {actual_size} != {expected_size}")
    if actual_hash != expected_hash:
        part.unlink(missing_ok=True)
        raise RuntimeError(f"sha256 mismatch: {actual_hash}")

    target.unlink(missing_ok=True)
    part.replace(target)
    print(f"SAVED {target}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--to-assets", action="store_true", help="write under app/src/main/assets/models")
    parser.add_argument("--verify-only", action="store_true", help="do not download missing files")
    args = parser.parse_args()

    target_dir = (
        ROOT / "app" / "src" / "main" / "assets" / "models"
        if args.to_assets
        else ROOT / "models"
    )
    print(f"Target: {target_dir}")

    if args.verify_only:
        ok = True
        for name, (size, digest) in MODELS.items():
            ok &= verify(target_dir / name, size, digest)
        return 0 if ok else 1

    for name, (size, digest) in MODELS.items():
        target = target_dir / name
        if verify(target, size, digest):
            continue
        download(target, size, digest)
        if not verify(target, size, digest):
            return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # noqa: BLE001 - CLI should show a clean error
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
