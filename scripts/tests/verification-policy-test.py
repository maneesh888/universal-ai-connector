#!/usr/bin/env python3
"""Exercise real Git scope/modes, trusted selection, CI outcomes, and stale metadata."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('policy', ROOT / 'scripts/verification-policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
ENV = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
# Hooks export Git-local variables; isolate all fixture subprocesses, including policy calls.
os.environ.clear()
os.environ.update(ENV)
ENV.update(GIT_AUTHOR_NAME='Policy Test', GIT_AUTHOR_EMAIL='policy@example.invalid',
           GIT_COMMITTER_NAME='Policy Test', GIT_COMMITTER_EMAIL='policy@example.invalid')


class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.write('README.md', 'baseline\n')
        self.write('docs/PRODUCTION_CONSUMPTION.md', 'baseline\n')
        self.write('source.kt', 'baseline\n')
        self.write('scripts/verification-policy.py', (ROOT/'scripts/verification-policy.py').read_text())
        self.commit()
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.git('update-ref', 'refs/remotes/origin/main', self.base)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], env=ENV, stderr=subprocess.DEVNULL).decode()

    def write(self, path, text):
        p = self.root/path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')

    def classify(self):
        return policy.classify(str(self.root), self.base, 'HEAD')

    def mode(self, stage='head', base='origin/main'):
        return subprocess.run(['bash', str(ROOT/'scripts/verification-mode.sh'), stage, base],
                              cwd=self.root, env=ENV, capture_output=True, text=True)

    def test_docs_and_staged_scope(self):
        self.write('README.md', 'updated\n')
        self.git('add', '.')
        self.assertEqual(self.mode('staged').stdout.strip(), 'docs')
        self.commit()
        self.assertEqual(self.classify(), 'docs')
        self.assertEqual(self.mode().stdout.strip(), 'docs')
        self.write('source.kt', 'source changed\n')
        self.git('add', '.')
        self.assertEqual(self.mode('staged').stdout.strip(), 'full')

    def test_mixed_docs_and_non_docs(self):
        for path in ('source.kt', 'config.json', 'gradle/libs.versions.toml', 'AGENTS.md',
                     '.agents/skills/example/SKILL.md', '.github/workflows/ci.yml',
                     'docs/plans/example.md', 'docs/DEVELOPMENT_WORKFLOW.md',
                     'docs/SECURITY_REVIEW.md', 'unknown.md'):
            with self.subTest(path=path):
                self.git('reset', '--hard', self.base)
                self.write('README.md', 'updated\n')
                self.write(path, 'changed\n')
                self.commit()
                self.assertEqual(self.classify(), 'full')

    def test_renames_modes_and_symlinks(self):
        self.git('mv', 'source.kt', 'README-new.md')
        self.commit()
        self.assertEqual(self.classify(), 'full')
        self.git('reset', '--hard', self.base)
        self.git('mv', 'README.md', 'docs/renamed.md')
        self.commit()
        self.assertEqual(self.classify(), 'full')
        self.git('reset', '--hard', self.base)
        (self.root/'README.md').chmod(0o755)
        self.commit()
        self.assertEqual(self.classify(), 'full')
        self.git('reset', '--hard', self.base)
        (self.root/'README.md').unlink()
        (self.root/'README.md').symlink_to('source.kt')
        self.commit()
        self.assertEqual(self.classify(), 'full')

    def test_unknown_and_missing_base(self):
        self.assertEqual(self.mode(base='missing').stdout.strip(), 'full')
        with self.assertRaises(subprocess.CalledProcessError):
            policy.classify(str(self.root), 'missing', 'HEAD')
        self.git('rm', 'scripts/verification-policy.py')
        self.commit()
        self.git('update-ref', 'refs/remotes/origin/main', self.git('rev-parse', 'HEAD').strip())
        self.write('README.md', 'changed\n')
        self.commit()
        self.assertEqual(self.mode().stdout.strip(), 'full')

    def test_candidate_cannot_replace_policy(self):
        self.write('scripts/verification-policy.py', 'print("docs")\n')
        self.write('source.kt', 'changed\n')
        self.commit()
        self.assertEqual(self.mode().stdout.strip(), 'full')

    def test_broken_or_unknown_trusted_output_fails(self):
        for code in ('print("unknown")\n', 'raise RuntimeError("broken")\n'):
            self.write('scripts/verification-policy.py', code)
            self.commit()
            self.git('update-ref', 'refs/remotes/origin/main', self.git('rev-parse', 'HEAD').strip())
            self.assertNotEqual(self.mode().returncode, 0)

    def test_ci_gate_actual_shell(self):
        # Execute the production aggregator, including negative/skipped statuses.
        workflow = (ROOT/'.github/workflows/ci.yml').read_text()
        job = workflow.split('  required-checks:\n', 1)[1]
        script = job.split('        run: |\n', 1)[1]
        script = '\n'.join(line[10:] for line in script.splitlines())
        cases = [('full', 'success', 'success', ['success']*3, True),
                 ('docs', 'success', 'success', ['skipped']*3, True),
                 ('', 'success', 'success', ['skipped']*3, False),
                 ('unknown', 'success', 'success', ['success']*3, False)]
        for status in ('failure', 'cancelled', 'skipped', ''):
            cases.append(('docs', status, 'success', ['skipped']*3, False))
            cases.append(('docs', 'success', status, ['skipped']*3, False))
        for status in ('failure', 'cancelled', 'skipped', ''):
            cases.append(('full', 'success', 'success', [status, 'success', 'success'], False))
            if status != 'skipped':
                cases.append(('docs', 'success', 'success', [status, 'skipped', 'skipped'], False))
        for classification, classifier, hygiene, platforms, expected in cases:
            with self.subTest(case=(classification, classifier, hygiene, platforms)):
                env = dict(ENV, CLASSIFICATION=classification, CLASSIFIER_RESULT=classifier,
                           HYGIENE_RESULT=hygiene, JVM_ANDROID_LINUX_RESULT=platforms[0],
                           JVM_WINDOWS_RESULT=platforms[1], APPLE_JVM_RESULT=platforms[2])
                result = subprocess.run(['bash', '-euc', script], env=env, capture_output=True)
                self.assertEqual(result.returncode == 0, expected)

    def test_ci_classifier_actual_shell(self):
        workflow = (ROOT/'.github/workflows/ci.yml').read_text()
        job = workflow.split('  classification:\n', 1)[1].split('  hygiene:\n', 1)[0]
        script = job.split('        run: |\n', 1)[1]
        script = '\n'.join(line[10:] for line in script.splitlines())
        with tempfile.TemporaryDirectory() as runner:
            output = Path(runner)/'output'
            def run(event='pull_request', head=None, base=None):
                output.write_text('')
                env = dict(ENV, EVENT_NAME=event, BASE_SHA=base or self.base,
                           HEAD_SHA=head or self.git('rev-parse', 'HEAD').strip(),
                           RUNNER_TEMP=runner, GITHUB_WORKSPACE=str(self.root), GITHUB_OUTPUT=str(output))
                result = subprocess.run(['bash', '-euc', script], cwd=self.root, env=env, capture_output=True)
                return result.returncode, output.read_text().strip()
            self.write('README.md', 'changed\n')
            self.commit()
            self.assertEqual(run(), (0, 'class=docs'))
            self.assertEqual(run('push'), (0, 'class=full'))
            self.assertEqual(run('workflow_dispatch'), (0, 'class=full'))
            self.assertNotEqual(run(head=self.base)[0], 0)
            self.assertNotEqual(run(base='missing')[0], 0)
            self.write('scripts/verification-policy.py', 'print("docs")\n')
            self.commit()
            self.assertEqual(run(), (0, 'class=full'))
            self.git('rm', 'scripts/verification-policy.py')
            self.commit()
            no_policy = self.git('rev-parse', 'HEAD').strip()
            self.write('README.md', 'changed again\n')
            self.commit()
            self.assertEqual(run(base=no_policy), (0, 'class=full'))

    def test_real_hooks_select_docs_and_keep_dirty_rejection(self):
        for name in ('verification-mode.sh',):
            self.write('scripts/'+name, (ROOT/'scripts'/name).read_text())
            (self.root/'scripts'/name).chmod(0o755)
        self.write('scripts/check.sh', '#!/bin/sh\nprintf "%s" "$1" > "$POLICY_CALL_LOG"\n')
        self.write('scripts/live-impact.sh', '#!/bin/sh\necho none\n')
        for name in ('check.sh', 'live-impact.sh'):
            (self.root/'scripts'/name).chmod(0o755)
        self.commit()
        self.git('update-ref', 'refs/remotes/origin/main', self.git('rev-parse', 'HEAD').strip())
        self.write('README.md', 'changed\n')
        self.git('add', '.')
        with tempfile.TemporaryDirectory() as logs:
            log = Path(logs)/'call'
            env = dict(ENV, POLICY_CALL_LOG=str(log))
            def hook(name, data=''):
                return subprocess.run(['bash', str(ROOT/'.githooks'/name)], cwd=self.root,
                                      env=env, input=data, text=True, capture_output=True)
            self.assertEqual(hook('pre-commit').returncode, 0)
            self.assertEqual(log.read_text(), '--hygiene')
            self.commit()
            head = self.git('rev-parse', 'HEAD').strip()
            self.assertEqual(hook('pre-push', f'refs/heads/test {head} refs/heads/test {"0"*40}\n').returncode, 0)
            self.assertEqual(log.read_text(), '--hygiene')
            self.write('README.md', 'unstaged\n')
            self.assertNotEqual(hook('pre-commit').returncode, 0)
            self.assertNotEqual(hook('pre-push').returncode, 0)

    def test_review_metadata(self):
        record = dict(route='local-security', base='a'*40, head='b'*40, scope='PR diff',
                      status='completed', reason='', findings=[], private_report='private/report.md')
        self.assertTrue(policy.review(record, 'b'*40))
        for status in ('pending', 'failed', 'unavailable', 'not-applicable'):
            item = dict(record, status=status, private_report='', reason='Recorded limitation')
            self.assertTrue(policy.review(item, 'b'*40))
        for update in ({'head': 'c'*40}, {'base': 'main'}, {'status': 'clean'},
                       {'private_report': ''}, {'scope': ''}, {'route': 'custom-reviewer'},
                       {'status': 'unavailable', 'reason': ''},
                       {'findings': [{'id': 'F1', 'disposition': 'dismissed', 'rationale': ''}]}):
            with self.subTest(update=update), self.assertRaises(ValueError):
                policy.review(dict(record, **update), 'b'*40)


if __name__ == '__main__':
    unittest.main()
