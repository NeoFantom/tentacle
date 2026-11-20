"""Offline setup and service tests; the service runner is always injected."""
from __future__ import annotations

import argparse
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest
from unittest import mock

from tentacle import config, requests_cmd


class RequestSetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / 'custom config.toml'
        self.path.write_text('[phone]\nurl = "https://example.com/tentacle"\ntoken = "test-token"\n'
                             '\n[storage]\ndata_dir = "data"\n', encoding='utf-8')
        self.parser = argparse.ArgumentParser()
        requests_cmd.add_commands(self.parser.add_subparsers(required=True))

    def setup(self, *argv):
        args = self.parser.parse_args(['requests', 'setup', *argv])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            status = args.func(args, config.load(self.path))
        return status, output.getvalue()

    def test_setup_writes_only_requests_and_updates_existing_values(self):
        self.assertEqual(self.setup('--command', 'my-agent --prompt-file {prompt_file}',
                                    '--repo', str(self.root), '--shell', 'bash -ic',
                                    '--release', 'fake-release', '--test', 'fake-test',
                                    '--push', 'git push local', '--timeout', '2h')[0], 0)
        parsed = tomllib.loads(self.path.read_text())
        self.assertEqual(parsed['phone']['token'], 'test-token')
        self.assertEqual(parsed['requests']['shell'], ['bash', '-ic'])
        self.assertEqual(parsed['requests']['timeout_s'], 7200)
        self.assertEqual(parsed['requests']['repo'], str(self.root))
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.setup('--command', 'new-fake-command')
        updated = config.load(self.path)
        self.assertEqual(updated.requests.command, 'new-fake-command')
        self.assertEqual(updated.requests.release, 'fake-release')
        self.assertEqual(self.path.read_text().count('[requests]'), 1)

    def test_setup_round_trips_shell_metacharacters_and_unicode(self):
        command = 'fake-agent --label "a # b" --note "Hello"'
        self.setup('--command', command)
        self.setup('--command', command + ' --prompt-file {prompt_file}')
        self.assertEqual(config.load(self.path).requests.command,
                         command + ' --prompt-file {prompt_file}')

    def test_invalid_setup_never_changes_config(self):
        original = self.path.read_text()
        for arguments in [('--command', ''), ('--command', 'fake', '--shell', ''),
                          ('--command', 'fake', '--timeout', '0s'),
                          ('--command', 'fake', '--timeout', 'forever')]:
            with self.subTest(arguments=arguments):
                with self.assertRaises(config.ConfigError):
                    self.setup(*arguments)
                self.assertEqual(self.path.read_text(), original)

    def test_setup_install_service_passes_config_path(self):
        with mock.patch.object(requests_cmd, 'install_service') as install:
            self.setup('--command', 'fake-agent', '--install-service')
        install.assert_called_once_with(self.path)

    def test_service_unit_and_runner_never_call_systemctl(self):
        calls, reports = [], []
        def runner(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 0)
        requests_cmd.install_service(self.path, runner=runner,
                                     unit_dir=self.root / 'units', report=reports.append)
        self.assertEqual(calls, [['systemctl', '--user', 'daemon-reload'],
                                 ['systemctl', '--user', 'enable', '--now', 'tentacle-requests.service']])
        unit = (self.root / 'units/tentacle-requests.service').read_text()
        self.assertIn('"-m" "tentacle.cli"', unit)
        self.assertIn('"--config" "' + str(self.path) + '"', unit)
        self.assertIn('"requests" "watch"', unit)
        self.assertIn('UMask=0077', unit)
        self.assertIn('systemctl --user daemon-reload (exit 0)', reports)
        self.assertEqual(len(reports), 3)

    def test_service_unavailable_prints_manual_command(self):
        reports = []
        def runner(argv, **kwargs):
            raise FileNotFoundError('no systemctl')
        requests_cmd.install_service(self.path, runner=runner,
                                     unit_dir=self.root / 'units', report=reports.append)
        self.assertTrue(any('Run manually: nohup ' in line and 'requests watch &' in line
                            and '--config' in line for line in reports))

    def test_service_enable_failure_prints_exact_attempts(self):
        reports = []
        calls = []
        def runner(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 1)
        requests_cmd.install_service(self.path, runner=runner,
                                     unit_dir=self.root / 'units', report=reports.append)
        self.assertEqual(len(calls), 1)
        self.assertIn('systemctl --user daemon-reload (exit 1)', reports)
        self.assertIn('Run manually: nohup ', reports[-1])

    def test_service_escapes_systemd_specifiers_and_quotes(self):
        unusual = self.root / 'path %n $VAR "quoted".toml'
        unit = requests_cmd.service_text(unusual)
        self.assertIn('%%n $$VAR \\"quoted\\"', unit)

    def test_list_human_and_json_preserve_phone_contract(self):
        request = dict(id='r-test', kind='feature', status='new', created_ms=1000,
                       text='First line\nSecond line', message='Waiting')
        payload = dict(requests=[request], now_ms=4000)
        for flag in ([], ['--json']):
            with self.subTest(flag=flag):
                args = self.parser.parse_args(['requests', 'list', *flag])
                with mock.patch.object(requests_cmd, 'PhoneClient') as client:
                    client.return_value.requests.return_value = payload
                    with contextlib.redirect_stdout(io.StringIO()) as out:
                        self.assertEqual(args.func(args, config.load(self.path)), 0)
                if flag:
                    self.assertEqual(json.loads(out.getvalue()), payload)
                else:
                    self.assertIn('r-test  feature  new  3s  First line  Waiting', out.getvalue())
                    self.assertNotIn('Second line', out.getvalue())

    def test_run_once_alias_and_timeout_units(self):
        alias = self.parser.parse_args(['requests', 'run-once'])
        once = self.parser.parse_args(['requests', 'watch', '--once'])
        self.assertTrue(alias.once and once.once)
        self.assertIs(alias.func, once.func)
        self.assertEqual(requests_cmd.timeout_seconds('0.1s'), 0.1)
        self.assertEqual(requests_cmd.timeout_seconds('10m'), 600)


if __name__ == '__main__':
    unittest.main()
