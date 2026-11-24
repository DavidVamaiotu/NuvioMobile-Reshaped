"""Exercise the real workflow shell step against a local GitHub API fixture."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/mirror-nuvio-release.yml'


class MirrorSelectionTest(unittest.TestCase):
    def select(self, existing, event='schedule', suffix='', upstream='1.1.0-beta.3'):
        text = WORKFLOW.read_text()
        step = text.split('      - name: Find latest official Nuvio release\n', 1)[1]
        shell = textwrap.dedent(step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0])
        shell = shell.replace('${{ inputs.release_suffix }}', '${TEST_RELEASE_SUFFIX}')
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            fixture = directory / 'fixture.json'
            fixture.write_text(json.dumps({'existing': existing, 'upstream': upstream}))
            mock = directory / 'gh'
            mock.write_text('''#!/usr/bin/env python3
import json, os, sys
fixture = json.load(open(os.environ['MOCK_RELEASE_FIXTURE']))
args = sys.argv[1:]
if args[0] == 'api':
    if any('NuvioMedia/NuvioMobile/releases' in a for a in args):
        print(json.dumps([{'tag_name': fixture['upstream'], 'draft': False}]))
    else:
        print('\\n'.join(fixture['existing']))
elif args[:2] == ['release', 'view']:
    sys.exit(0 if args[2] in fixture['existing'] else 1)
else:
    raise SystemExit('Unexpected gh request: ' + repr(args))
''')
            mock.chmod(0o755)
            output = directory / 'output'
            env = os.environ.copy()
            env.update(PATH=str(directory) + os.pathsep + env['PATH'], MOCK_RELEASE_FIXTURE=str(fixture),
                       GITHUB_OUTPUT=str(output), GITHUB_EVENT_NAME=event,
                       GITHUB_REPOSITORY='example/fork', TEST_RELEASE_SUFFIX=suffix)
            subprocess.run(['bash', '-e', '-o', 'pipefail'], input=shell, text=True, cwd=ROOT,
                           env=env, check=True, capture_output=True)
            return dict(line.split('=', 1) for line in output.read_text().splitlines())

    def test_schedule_reuses_numbered_release_when_bare_tag_is_absent(self):
        result = self.select(['1.1.0-beta.3-autosync.1'])
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.3-autosync.1')
        self.assertEqual(result['already_exists'], 'true')

    def test_default_dispatch_reuses_numbered_release(self):
        result = self.select(['1.1.0-beta.3-autosync.1'], 'workflow_dispatch', 'autosync')
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.3-autosync.1')
        self.assertEqual(result['already_exists'], 'true')

    def test_schedule_reuses_latest_numbered_release_even_with_bare_tag(self):
        result = self.select(['1.1.0-beta.3-autosync', '1.1.0-beta.3-autosync.2', '1.1.0-beta.3-autosync.10'])
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.3-autosync.10')
        self.assertEqual(result['already_exists'], 'true')

    def test_workflow_push_still_requests_next_revision(self):
        result = self.select(['1.1.0-beta.3-autosync.1'], 'push')
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.3-autosync.2')
        self.assertEqual(result['already_exists'], 'false')

    def test_explicit_dispatch_revision_is_preserved(self):
        result = self.select(['1.1.0-beta.3-autosync.1'], 'workflow_dispatch', 'autosync.2')
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.3-autosync.2')

    def test_first_numeric_beta_uses_numbered_tag_for_legacy_updaters(self):
        result = self.select([], upstream='1.1.0-beta.4')
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.4-autosync.1')
        self.assertEqual(result['already_exists'], 'false')

    def test_first_numeric_beta_workflow_push_uses_same_numbering(self):
        result = self.select([], 'push', upstream='1.1.0-beta.4')
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.4-autosync.1')

    def test_first_stable_release_preserves_bare_tag_convention(self):
        result = self.select([], upstream='1.1.0')
        self.assertEqual(result['autosync_tag'], '1.1.0-autosync')

    def test_existing_bare_beta_remains_a_noop(self):
        result = self.select(['1.1.0-beta.3-autosync'])
        self.assertEqual(result['autosync_tag'], '1.1.0-beta.3-autosync')
        self.assertEqual(result['already_exists'], 'true')

    def test_version_prefix_and_build_metadata_are_preserved(self):
        result = self.select([], upstream='v1.1.0-beta.4+build.9')
        self.assertEqual(result['autosync_tag'], 'v1.1.0-beta.4+build.9-autosync.1')


if __name__ == '__main__':
    unittest.main()
