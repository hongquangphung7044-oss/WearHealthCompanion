#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

from scripts.update_readme_build_status import update_readme


INITIAL = """before
<!-- AUTO_BUILD_STATUS:START -->
| 自动发布项目 | 最新成功状态 |
|---|---|
| CI 构建 | **Build #57** / tag `build-57` / source commit `1234567` |
<!-- AUTO_BUILD_STATUS:END -->
after: real-device findings must remain unchanged
"""


class UpdateReadmeBuildStatusTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.path = Path(self.temp_dir.name) / "README.md"
        self.path.write_text(INITIAL, encoding="utf-8")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def update(self, build: int) -> bool:
        return update_readme(
            self.path,
            build=build,
            run_id=9000 + build,
            source_sha="abcdef0123456789",
            repository="owner/repository",
            generated_at="2026-07-14T00:00:00Z",
        )

    def test_updates_generated_block_only(self) -> None:
        self.assertTrue(self.update(58))
        text = self.path.read_text(encoding="utf-8")
        self.assertIn("**Build #58**", text)
        self.assertIn("actions/runs/9058", text)
        self.assertIn("mobile-v1.0.58-code58.apk", text)
        self.assertIn("after: real-device findings must remain unchanged", text)
        self.assertEqual(1, text.count("<!-- AUTO_BUILD_STATUS:START -->"))
        self.assertEqual(1, text.count("<!-- AUTO_BUILD_STATUS:END -->"))

    def test_same_or_older_build_cannot_overwrite_status(self) -> None:
        original = self.path.read_text(encoding="utf-8")
        self.assertFalse(self.update(57))
        self.assertFalse(self.update(56))
        self.assertEqual(original, self.path.read_text(encoding="utf-8"))

    def test_missing_markers_fail_closed(self) -> None:
        self.path.write_text("no generated block", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.update(58)


if __name__ == "__main__":
    unittest.main()
