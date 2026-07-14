#!/usr/bin/env python3
"""Update only README's generated successful-build status block.

The hand-written device-validation status is intentionally outside this block: a green CI run
must never be presented as proof that the China-ROM BLE path passed on real hardware.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

START = "<!-- AUTO_BUILD_STATUS:START -->"
END = "<!-- AUTO_BUILD_STATUS:END -->"
BUILD_PATTERN = re.compile(r"\*\*Build #(\d+)\*\*")


def update_readme(
    path: Path,
    *,
    build: int,
    run_id: int,
    source_sha: str,
    repository: str,
    generated_at: str,
) -> bool:
    text = path.read_text(encoding="utf-8")
    if text.count(START) != 1 or text.count(END) != 1:
        raise ValueError("README must contain exactly one auto-build status marker pair")
    start = text.index(START)
    end = text.index(END, start) + len(END)
    current_block = text[start:end]
    current_match = BUILD_PATTERN.search(current_block)
    if current_match is None:
        raise ValueError("generated README block does not contain a build number")
    current_build = int(current_match.group(1))
    # Concurrent runs can complete out of order. Never let an older run overwrite a newer status.
    if build <= current_build:
        return False

    short_sha = source_sha[:7]
    block = "\n".join(
        [
            START,
            "| 自动发布项目 | 最新成功状态 |",
            "|---|---|",
            f"| CI 构建 | **Build #{build}** / tag `build-{build}` / source commit `{short_sha}` |",
            f"| Actions | `https://github.com/{repository}/actions/runs/{run_id}`（协议测试、双 Release APK、Artifact、Release 全部成功） |",
            f"| Release | `https://github.com/{repository}/releases/tag/build-{build}` |",
            f"| APK | `WearHealthCompanion-mobile-v1.0.{build}-code{build}.apk`；`WearHealthCompanion-watch-v1.0.{build}-code{build}-ecg.apk` |",
            f"| 更新时间 | {generated_at} |",
            "| 状态边界 | 仅证明 CI / Artifact / Release 成功；国行 One UI Watch 8 实机结论以“实机验证状态”和任务清单为准 |",
            END,
        ]
    )
    updated = text[:start] + block + text[end:]
    if updated == text:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, default=Path("README.md"))
    parser.add_argument("--build", type=int, required=True)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--generated-at", required=True)
    args = parser.parse_args()
    if args.build <= 0 or args.run_id <= 0:
        parser.error("build and run-id must be positive")
    if len(args.source_sha) < 7:
        parser.error("source-sha must contain at least seven characters")
    changed = update_readme(
        args.file,
        build=args.build,
        run_id=args.run_id,
        source_sha=args.source_sha,
        repository=args.repository,
        generated_at=args.generated_at,
    )
    print("README build status updated" if changed else "README build status already current or newer")


if __name__ == "__main__":
    main()
