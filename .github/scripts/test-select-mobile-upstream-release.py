import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    "selector", Path(__file__).with_name("select-mobile-upstream-release.py")
)
selector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selector)


def releases(*tags):
    return [{"tag_name": tag, "draft": False} for tag in tags]


class UpstreamReleaseSelectionTest(unittest.TestCase):
    def test_later_published_maintenance_release_does_not_regress_beta_clients(self):
        self.assertEqual(selector.select_release(releases("1.0.1", "1.1.0-beta.2")), "1.1.0-beta.2")

    def test_stable_supersedes_same_core_prerelease(self):
        self.assertEqual(selector.select_release(releases("1.1.0-beta.10", "1.1.0")), "1.1.0")

    def test_numeric_prerelease_identifiers(self):
        self.assertEqual(selector.select_release(releases("1.1.0-beta.2", "1.1.0-beta.10")), "1.1.0-beta.10")

    def test_prefix_and_build_metadata_preserve_original_tag(self):
        self.assertEqual(selector.select_release(releases("1.0.1", "v1.1.0+build.4")), "v1.1.0+build.4")

    def test_draft_and_invalid_tags_are_excluded(self):
        rows = releases("latest", "1.2.0-beta.01", "1.0.1")
        rows.append({"tag_name": "9.0.0", "draft": True})
        self.assertEqual(selector.select_release(rows), "1.0.1")

    def test_paginated_slurped_response(self):
        self.assertEqual(selector.select_release([releases("1.0.1"), releases("1.1.0-beta.2")]), "1.1.0-beta.2")

    def test_semver_prerelease_precedence(self):
        order = ["1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"]
        for lower, higher in zip(order, order[1:]):
            self.assertEqual(selector.select_release(releases(higher, lower)), higher)

    def test_no_valid_release_fails(self):
        with self.assertRaisesRegex(ValueError, "No published"):
            selector.select_release(releases("latest"))


if __name__ == "__main__":
    unittest.main()
