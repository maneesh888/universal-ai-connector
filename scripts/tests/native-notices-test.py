#!/usr/bin/env python3
"""Offline regressions for native notice integrity and dependency drift."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('notices', ROOT / 'scripts/native-notices.py')
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class NativeNoticesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / 'distribution', self.root / 'distribution')
        shutil.copyfile(ROOT / 'LICENSE', self.root / 'LICENSE')
        self.manifest = self.root / 'distribution/licenses/manifest.json'
        self.data = json.loads(self.manifest.read_text())
        self.resolved = self.root / 'inputs.tsv'
        self.resolved.write_text(''.join('\t'.join(row[k] for k in ('configuration', 'coordinate', 'artifact', 'sha256')) + '\n' for row in self.data['inputs']))
        self.destination = self.root / 'Framework.xcframework'
        self.destination.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def test_stage_and_verify_exact_files(self):
        notices.validate(self.root, self.resolved, self.destination)
        notices.validate(self.root, self.resolved, self.destination, verify=True)
        (self.destination / 'LICENSE').unlink()
        with self.assertRaises(ValueError):
            notices.validate(self.root, self.resolved, self.destination, verify=True)

    def test_changed_or_missing_notice(self):
        path = self.root / 'distribution/licenses' / next(iter(self.data['notices']))
        path.write_text('changed')
        with self.assertRaises(ValueError):
            notices.validate(self.root)
        path.unlink()
        with self.assertRaises(ValueError):
            notices.validate(self.root)

    def test_changed_dependency(self):
        self.resolved.write_text(self.resolved.read_text().replace('io.ktor:', 'other:', 1))
        with self.assertRaises(ValueError):
            notices.validate(self.root, self.resolved)

    def test_changed_compiler(self):
        self.data['kotlin'] = '0.0.0'
        self.manifest.write_text(json.dumps(self.data))
        with self.assertRaises(ValueError):
            notices.validate(self.root)

    def test_path_escape(self):
        self.data['notices']['../escape'] = {'sha256': '0' * 64}
        self.manifest.write_text(json.dumps(self.data))
        with self.assertRaises(ValueError):
            notices.validate(self.root)

    def test_destination_symlink(self):
        (self.destination / 'THIRD_PARTY_NOTICES').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            notices.validate(self.root, self.resolved, self.destination)

    def test_verify_rejects_matching_external_tree(self):
        notices.validate(self.root, self.resolved, self.destination)
        tree = self.destination / 'THIRD_PARTY_NOTICES'
        external = self.root / 'external-notices'
        tree.rename(external)
        tree.symlink_to(external, target_is_directory=True)
        with self.assertRaises(ValueError):
            notices.validate(self.root, self.resolved, self.destination, verify=True)

    def test_verify_rejects_dangling_extra_link(self):
        notices.validate(self.root, self.resolved, self.destination)
        (self.destination / 'THIRD_PARTY_NOTICES/extra').symlink_to(self.root / 'absent')
        with self.assertRaises(ValueError):
            notices.validate(self.root, self.resolved, self.destination, verify=True)


if __name__ == '__main__':
    unittest.main()
