#!/usr/bin/env python3
"""Rebuild the bundled dsh runtime (app/src/main/assets/dsh-runtime.tar.xz).

Why this script exists: the runtime asset used to have *no* generator in the
repo. It was assembled by hand once, so "which packages does the bundled dsh
contain" was unanswerable and unreproducible. The visible consequence was that
`dsh --profile web` could not start from it (39 `dsh-client-ui-*` packages were
missing), which forced Heta to keep a *second*, npm-installed dsh in the user's
Linux environment just to serve the Web UI.

What it does: take the current asset as the base (it carries the node binary,
the glibc set and the trimmed dependency tree), scan the profile bundles for
`@deepseek-ai/*` references, and fetch whatever is referenced but absent from
the npm registry at the version the runtime already pins. Then repack.

Usage:
    python3 scripts/build-dsh-runtime.py [--check]

`--check` reports what would change without touching the asset (exit 1 if the
asset is stale), which is what CI uses to notice drift.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tarfile
import time
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ASSET = REPO / "app" / "src" / "main" / "assets" / "dsh-runtime.tar.xz"

# The runtime only ever boots one profile: `acp`, which is what the in-app chat
# drives. dsh-base is the floor under it.
#
# `web` is deliberately *not* here. Heta has its own UI, so dsh's browser console
# was removed and the ~39 `dsh-client-ui-*` packages it needs are not bundled —
# they were +2.1 MB of APK for a surface nobody opens. If the Web UI ever comes
# back, add `dsh-web-app` to this tuple and rebuild.
BUNDLES = ("dsh-base", "dsh-acp-app")

# Referenced from somewhere in the tree, but never at runtime: test kits, mock
# servers, code generators, and the other platforms' native addons. Pulling
# them in would only bloat the APK.
EXCLUDED = {
    # tests / mocks / generators
    "dsh-agent-loop-testkit",
    "dsh-client-test-runtime",
    "dsh-llm-mock-server",
    "dsh-loader-smoke",
    "dsh-typert-generator",
    "dsh-experimental-webworker-packer",
    "dsh-experimental-webworker-runtime",
    # other platforms
    "node-addon-system-darwin-arm64",
    "node-addon-system-darwin-x64",
    "node-addon-system-linux-x64",
    "node-addon-system-win32-x64",
    "dsh-win32-process",
    # the application package itself — it lives at opt/dsh, not in node_modules
    "dsh",
}

DEFAULT_REGISTRY = "https://registry.npmjs.org"
PACKAGE_PREFIX = "@deepseek-ai/"
SCOPE_DIR = "opt/dsh/node_modules/@deepseek-ai"
DSH_PACKAGE_JSON = "opt/dsh/package.json"


def log(message: str) -> None:
    print(message, flush=True)


def run(command: list[str]) -> None:
    subprocess.run(command, check=True)


# --- registry ---------------------------------------------------------------


def fetch_json(url: str, attempts: int = 5) -> dict:
    """GET+parse JSON, retrying the resets and 5xx the public registry throws."""
    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(url, headers={"User-Agent": "heta-dsh-runtime-build"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise
            last = error
        except Exception as error:  # noqa: BLE001 — every transport error here is retryable
            last = error
        log(f"    registry 请求失败（第 {attempt}/{attempts} 次），重试：{url}")
        time.sleep(min(2**attempt, 15))
    raise SystemExit(f"registry 不可用: {url} ({last})")


def package_metadata(name: str, version: str, registry: str) -> dict | None:
    """Metadata for one exact version, or None when the registry has no such package.

    Some names only ever appear as prose inside the tree (a log message, a
    doc comment, a renamed-away package), so a 404 is expected and means "this
    is not a dependency" — the boot check in the release process is what proves
    the collected set is actually sufficient.
    """
    url = f"{registry}/{PACKAGE_PREFIX}{name}/{version}"
    try:
        return fetch_json(url)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


# --- bundle scanning --------------------------------------------------------


def referenced_packages(root: Path) -> set[str]:
    """Every `@deepseek-ai/*` package the booted bundles mention.

    Scoped to [BUNDLES] on purpose. Scanning every installed package instead
    drags in the peers of plugins Heta never loads — that is how the Web UI's
    39 `dsh-client-ui-*` packages got collected, and they are no longer wanted.

    The name pattern requires an alphanumeric tail, otherwise the trailing `-`
    in generated code (`@deepseek-ai/dsh-${kind}`) shows up as a package called
    `dsh-`.
    """
    found: set[str] = set()
    scope = root / SCOPE_DIR
    pattern = re.compile(re.escape(PACKAGE_PREFIX) + r"([a-z0-9][a-z0-9-]*[a-z0-9])")
    for bundle in BUNDLES:
        directory = scope / bundle
        if not directory.is_dir():
            raise SystemExit(f"bundle not found in runtime: {bundle}")
        for path in directory.rglob("*"):
            if not path.is_file() or path.suffix not in {".yml", ".yaml", ".js", ".mjs", ".cjs", ".json"}:
                continue
            text = path.read_text(errors="ignore")
            found.update(pattern.findall(text))
    return found - EXCLUDED


def installed_packages(root: Path) -> set[str]:
    scope = root / SCOPE_DIR
    return {entry.name for entry in scope.iterdir() if entry.is_dir()}


def resolve_closure(
    root: Path,
    wanted: set[str],
    version: str,
    registry: str,
) -> list[tuple[str, dict]]:
    """Expand `wanted` with the `@deepseek-ai/*` dependencies it drags in.

    Returns the packages to install, in install order, each with its metadata.
    """
    have = installed_packages(root)
    pending = sorted(wanted - have)
    planned: list[tuple[str, dict]] = []
    seen: set[str] = set()
    while pending:
        name = pending.pop(0)
        if name in seen:
            continue
        seen.add(name)
        metadata = package_metadata(name, version, registry)
        if metadata is None:
            log(f"  跳过 {name}：registry 上不存在（只是代码里的字符串，不是依赖）")
            continue
        planned.append((name, metadata))
        for dependency in metadata.get("dependencies", {}):
            if not dependency.startswith(PACKAGE_PREFIX):
                continue
            short = dependency[len(PACKAGE_PREFIX) :]
            if short not in have and short not in seen:
                pending.append(short)
    return planned


# --- asset io ---------------------------------------------------------------


def extract(asset: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    run(["tar", "-xJf", str(asset), "-C", str(destination)])


def install_package(root: Path, name: str, metadata: dict, scratch: Path) -> None:
    tarball = scratch / f"{name}.tgz"
    request = urllib.request.Request(
        metadata["dist"]["tarball"],
        headers={"User-Agent": "heta-dsh-runtime-build"},
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        tarball.write_bytes(response.read())

    unpacked = scratch / f"{name}.d"
    if unpacked.exists():
        shutil.rmtree(unpacked)
    unpacked.mkdir(parents=True)
    with tarfile.open(tarball) as archive:
        archive.extractall(unpacked, filter="data")

    target = root / SCOPE_DIR / name
    if target.exists():
        shutil.rmtree(target)
    # npm tarballs always wrap their payload in a single `package/` directory.
    shutil.move(str(unpacked / "package"), str(target))
    tarball.unlink()
    shutil.rmtree(unpacked)


def repack(root: Path, asset: Path) -> None:
    """Deterministic tar.xz: same inputs must produce byte-identical output."""
    staging = asset.with_suffix(".tar.xz.new")
    with staging.open("wb") as handle:
        tar = subprocess.Popen(
            [
                "tar",
                "-C", str(root),
                "--sort=name",
                "--owner=0", "--group=0", "--numeric-owner",
                "--mtime=@0",
                "--format=gnu",
                "-cf", "-",
                ".",
            ],
            stdout=subprocess.PIPE,
        )
        xz = subprocess.Popen(["xz", "-9", "-T0", "-c"], stdin=tar.stdout, stdout=handle)
        tar.stdout.close()  # type: ignore[union-attr]
        if xz.wait() != 0 or tar.wait() != 0:
            staging.unlink(missing_ok=True)
            raise SystemExit("repack failed")
    staging.replace(asset)


# --- entry point ------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report drift, change nothing")
    parser.add_argument("--registry", default=DEFAULT_REGISTRY)
    args = parser.parse_args()

    if not ASSET.is_file():
        raise SystemExit(f"missing asset: {ASSET}")

    before = ASSET.stat().st_size
    with tempfile.TemporaryDirectory(prefix="dsh-runtime-") as temporary:
        work = Path(temporary)
        root = work / "root"
        scratch = work / "scratch"
        scratch.mkdir()

        log(f"解包 {ASSET.name} …")
        extract(ASSET, root)

        version = json.loads((root / DSH_PACKAGE_JSON).read_text())["version"]
        log(f"运行时 dsh 版本: {version}")

        for bundle in BUNDLES:
            if not (root / SCOPE_DIR / bundle).is_dir():
                raise SystemExit(f"bundle not found in runtime: {bundle}")

        added = 0
        while True:
            referenced = referenced_packages(root)
            have = installed_packages(root)
            missing = referenced - have
            if not missing:
                break
            log(f"引用 {len(referenced)} 个包，现有 {len(have)} 个，缺 {len(missing)} 个")

            planned = resolve_closure(root, missing, version, args.registry)
            if args.check:
                log("缺失（--check 不下载）:")
                for name, _ in planned:
                    log(f"  - {name}")
                log("运行时已过期：请运行 python3 scripts/build-dsh-runtime.py")
                return 1
            if not planned:
                # 剩下的名字在 registry 上都不存在，说明它们只是代码里的字符串。
                # 不 break 就会原地打转。
                log(f"剩下 {len(missing)} 个名字都取不到，按「不是依赖」处理：{sorted(missing)}")
                break

            for name, metadata in planned:
                log(f"  补 {name}@{metadata.get('version')}")
                install_package(root, name, metadata, scratch)
            added += len(planned)

        if added == 0:
            log("运行时已是最新，无需改动。")
            return 0

        log(f"共补 {added} 个包，重新打包 …")
        repack(root, ASSET)

    after = ASSET.stat().st_size
    log(f"完成: {before:,} -> {after:,} bytes ({(after - before) / 1024 / 1024:+.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
