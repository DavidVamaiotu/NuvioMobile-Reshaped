import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("mobile_release_version", Path(__file__).with_name("mobile-release-version.py"))
release = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = release
spec.loader.exec_module(release)


class MobileReleaseVersionTest(unittest.TestCase):
    def test_first_release_uses_official_code(self):
        self.assertEqual(153, release.next_code(153))

    def test_fork_revision_advances_reused_official_code(self):
        self.assertEqual(154, release.next_code(153, 153))

    def test_next_official_release_cannot_collide_with_fork_revision(self):
        self.assertEqual(155, release.next_code(154, 154))
        self.assertEqual(200, release.next_code(200, 154))

    def test_invalid_or_exhausted_codes_fail(self):
        for official, previous in [(0, 0), (153, -1), (153, release.MAX_CODE), (release.MAX_CODE + 1, 0)]:
            with self.subTest(official=official, previous=previous), self.assertRaises(ValueError):
                release.next_code(official, previous)

    def test_previous_apk_is_selected_by_publication_time(self):
        def entry(tag, date, draft=False, asset="NuvioRS-version-arm64.apk"):
            return dict(tag_name=tag, published_at=date, draft=draft, assets=[dict(name=asset)])
        self.assertEqual("revision", release.previous_release([
            entry("old", "2026-11-08T10:00:00Z"),
            entry("draft", "2026-11-10T10:00:00Z", draft=True),
            entry("unrelated", "2026-11-10T10:00:00Z", asset="notes.txt"),
            entry("revision", "2026-11-09T10:00:00Z"),
        ]))
        self.assertEqual("", release.previous_release([]))

    def test_manifest_must_belong_to_mobile_fork(self):
        self.assertEqual((153, "release"), release.package_info("package: name='com.nuvioreshaped.app' versionCode='153' versionName='release'"))
        with self.assertRaises(ValueError):
            release.package_info("package: name='com.nuvioreshaped.tv' versionCode='153' versionName='release'")

    def test_equal_code_and_changed_identity_are_rejected(self):
        previous = release.Apk(153, "old", frozenset({"key"}))
        for current in [release.Apk(153, "new", previous.signers), release.Apk(154, "new", frozenset({"other"}))]:
            with self.subTest(current=current), self.assertRaises(ValueError):
                release.verify([current], previous, current.code, "new")

    def test_all_architectures_must_match_the_release(self):
        previous = release.Apk(153, "old", frozenset({"key"}))
        current = release.Apk(154, "new", previous.signers)
        release.verify([current] * 4, previous, 154, "new")
        with self.assertRaises(ValueError):
            release.verify([current, release.Apk(153, "new", previous.signers)], previous, 154, "new")


if __name__ == "__main__":
    unittest.main()
