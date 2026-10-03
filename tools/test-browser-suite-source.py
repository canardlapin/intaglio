"""Regression checks for the browser runner's source provenance; no browser or sbt needed."""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "browser_suites", Path(__file__).with_name("check-browser-suites.py")
)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class CommitSourceTest(unittest.TestCase):
    def test_working_edits_and_new_head_cannot_change_the_recorded_source(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)

            def git(*args):
                return subprocess.check_output(["git", *args], cwd=repository, text=True)

            git("init", "-q")
            tracked = repository / "Fixture.scala"
            tracked.write_text("object Original\n")
            git("add", "Fixture.scala")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "-qm", "original")
            commit = git("rev-parse", "HEAD").strip()
            tracked.write_text("object Dirty\n")
            (repository / "Untracked.scala").write_text("object Untracked\n")
            with runner.commit_source(repository, commit) as source:
                self.assertEqual((source / "Fixture.scala").read_text(), "object Original\n")
                self.assertFalse((source / "Untracked.scala").exists())
                git("add", "Fixture.scala")
                git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                    "commit", "-qm", "concurrent change")
                self.assertNotEqual(git("rev-parse", "HEAD").strip(), commit)
                self.assertEqual((source / "Fixture.scala").read_text(), "object Original\n")
                (source / "target").mkdir()
                (source / "target" / "build-output").write_text("owned temporary output")
            self.assertFalse(source.exists())
            self.assertEqual(tracked.read_text(), "object Dirty\n")
            self.assertTrue((repository / "Untracked.scala").exists())


if __name__ == "__main__":
    unittest.main()
