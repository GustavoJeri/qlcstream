"""Exercise the runtime helper with owned temporary files and real Linux descriptors."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "backend/docker/library-file-delete.py"
spec = importlib.util.spec_from_file_location("deletion", SCRIPT)
deletion = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deletion)


@unittest.skipUnless(os.name == "posix", "The Docker helper requires POSIX directory descriptors")
class FileDeletionTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="qlc-owned-deletion-")
        self.addCleanup(self.scratch.cleanup)
        self.base = Path(self.scratch.name)
        self.root = self.base / "media"
        self.root.mkdir()
        self.outside = self.base / "outside"
        self.outside.mkdir()
        (self.outside / "owned.mp4").write_bytes(b"external marker")

    def test_selected_file_missing_retry_and_sibling_preservation(self):
        folder = self.root / "season"
        folder.mkdir()
        selected = folder / "ação com espaços.mp4"
        selected.write_bytes(b"owned selected")
        sibling = folder / "other.mp4"
        sibling.write_bytes(b"owned sibling")
        deletion.operate(str(self.root), "season/ação com espaços.mp4", False)
        self.assertTrue(selected.exists())
        for _ in range(2):
            deletion.operate(str(self.root), "season/ação com espaços.mp4", True)
        deletion.operate(str(self.root), "absent/owned.mp4", True)
        self.assertFalse(selected.exists())
        self.assertEqual(sibling.read_bytes(), b"owned sibling")
        posix_name = folder / "Film: Part II.mp4"
        posix_name.write_bytes(b"owned with colon")
        deletion.operate(str(self.root), "season/Film: Part II.mp4", True)
        self.assertFalse(posix_name.exists())

    def test_invalid_representations_and_all_link_positions_are_refused(self):
        (self.root / "linked").symlink_to(self.outside, target_is_directory=True)
        (self.root / "leaf.mp4").symlink_to(self.outside / "owned.mp4")
        (self.root / "normal").mkdir()
        (self.root / "normal/nested").symlink_to(self.outside, target_is_directory=True)
        for relative in ("", ".", "../outside/owned.mp4", "/outside/owned.mp4", "C:/owned.mp4",
                         "\\\\server\\owned.mp4", "linked/owned.mp4", "normal/nested/owned.mp4", "leaf.mp4", "normal"):
            with self.subTest(relative=relative):
                with self.assertRaises((ValueError, OSError)):
                    deletion.operate(str(self.root), relative, True)
        alias = self.base / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError):
            deletion.operate(str(alias), "owned.mp4", True)
        self.assertEqual((self.outside / "owned.mp4").read_bytes(), b"external marker")

    def test_parent_replacement_after_traversal_cannot_redirect_unlink(self):
        folder = self.root / "season"
        folder.mkdir()
        (folder / "owned.mp4").write_bytes(b"authorized")
        original_stat = os.stat
        replaced = False

        def replace_after_pin(name, *args, **kwargs):
            nonlocal replaced
            if name == "owned.mp4" and kwargs.get("dir_fd") is not None and not replaced:
                replaced = True
                folder.rename(self.root / "pinned")
                folder.symlink_to(self.outside, target_is_directory=True)
            return original_stat(name, *args, **kwargs)

        with patch.object(deletion.os, "stat", side_effect=replace_after_pin):
            deletion.operate(str(self.root), "season/owned.mp4", True)
        self.assertTrue(replaced)
        self.assertFalse((self.root / "pinned/owned.mp4").exists())
        self.assertEqual((self.outside / "owned.mp4").read_bytes(), b"external marker")


if __name__ == "__main__":
    unittest.main()
