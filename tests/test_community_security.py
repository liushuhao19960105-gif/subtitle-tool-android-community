"""Public repository must remain independent of private release signing."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]

class PublicSafety(unittest.TestCase):
    def test_no_official_signer(self):
        gradle=(ROOT/"app/build.gradle").read_text()
        ci=(ROOT/".github/workflows/community-ci.yml").read_text()
        for forbidden in ("SUBTITLE_SIGNING_", "secrets.", "signingConfigs.persistent", "setup_stable_signing", "pull_request_target"):
            self.assertNotIn(forbidden,gradle)
            self.assertNotIn(forbidden,ci)
        self.assertNotIn("workflow_dispatch:",ci)
        self.assertIn("persist-credentials: false",ci)
        self.assertIn("contents: read",ci)
        self.assertIn("assembleDebug",ci)

    def test_private_release_material_absent(self):
        # A normal GitHub checkout has a .git directory; it must contain only this repository's history.
        for forbidden in ("setup_stable_signing.sh", "publish_to_github.sh",
                          "V181_STABLE_SIGNING.md", ".github/workflows/android-apk.yml"):
            self.assertFalse((ROOT/forbidden).exists(),forbidden)

    def test_license_and_privacy(self):
        self.assertIn("Apache License", (ROOT/"LICENSE").read_text())
        self.assertIn("Deepgram",(ROOT/"PRIVACY.md").read_text())
        self.assertTrue((ROOT/"SECURITY.md").exists())
        self.assertTrue((ROOT/"CODE_OF_CONDUCT.md").exists())

if __name__=="__main__":
    unittest.main()
