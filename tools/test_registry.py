"""Offline tests: no credentials, publication, DNS, or live registry required."""
import importlib.util
import pathlib
import tempfile
import unittest
import urllib.parse
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('registry', pathlib.Path(__file__).with_name('registry.py'))
registry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(registry)


class RegistryTest(unittest.TestCase):
    def test_all_states_are_explicitly_queried(self):
        with patch.object(registry, 'read_json', return_value=([], '')) as read:
            registry.require_absent(['ratelimitly-java-client'], '3.0.0')
            statuses = {urllib.parse.parse_qs(urllib.parse.urlsplit(call.args[0]).query)['status'][0]
                        for call in read.call_args_list}
            self.assertEqual(statuses, {'default', 'hidden', 'processing', 'error',
                                       'pending_destruction', 'deprecated'})

    def test_empty_registry_allows_new_coordinate(self):
        with patch.object(registry, 'read_json', return_value=([], '')):
            registry.require_absent(['ratelimitly-java-client'], '3.0.0')

    def test_partial_or_complete_existing_coordinate_blocks_upload(self):
        for status in ('default', 'processing', 'error'):
            record = dict(name='com/ratelimitly/ratelimitly-java-client', version='3.0.0', status=status)
            with patch.object(registry, 'read_json', return_value=([record], '')):
                with self.assertRaisesRegex(ValueError, 'already exists'):
                    registry.require_absent(['ratelimitly-java-client'], '3.0.0')

    def test_pagination_is_not_ignored(self):
        record = dict(name='com/ratelimitly/ratelimitly-java-client', version='3.0.0')
        with patch.object(registry, 'read_json', side_effect=[([], '2'), ([record], '')]):
            with self.assertRaises(ValueError):
                registry.require_absent(['ratelimitly-java-client'], '3.0.0')

    def test_registry_errors_do_not_mean_absent(self):
        with patch.object(registry, 'read_json', side_effect=OSError('unreachable')):
            with self.assertRaises(OSError):
                registry.require_absent(['ratelimitly-java-client'], '3.0.0')

    def test_input_cannot_escape_coordinate_path(self):
        for version in ('../3.0.0', '3.0.0-SNAPSHOT', '3.0.0?token=x'):
            with self.assertRaises(ValueError):
                registry.require_absent(['ratelimitly-java-client'], version)

    def test_anonymous_download_must_equal_reviewed_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'artifact.jar'
            path.write_bytes(b'reviewed')
            with patch.object(registry, 'read_bytes', return_value=b'different'):
                with self.assertRaisesRegex(ValueError, 'differs'):
                    registry.compare_download('https://gitlab.com/file', path)

    def test_failed_or_wrong_signature_identity_is_rejected(self):
        fingerprint = 'A' * 40
        registry.check_signature_status('[GNUPG:] VALIDSIG ' + fingerprint + ' 0 0', fingerprint)
        for status in ('', '[GNUPG:] BADSIG ' + fingerprint,
                       '[GNUPG:] VALIDSIG ' + 'B' * 40 + ' 0 0',
                       '[GNUPG:] EXPKEYSIG ' + fingerprint):
            with self.assertRaises(ValueError):
                registry.check_signature_status(status, fingerprint)


if __name__ == '__main__':
    unittest.main()
