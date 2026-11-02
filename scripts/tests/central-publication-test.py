#!/usr/bin/env python3
"""Publisher protocol, evidence and recovery fixtures. No real HTTP, token or GPG."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from unittest.mock import Mock
import urllib.error
import zipfile


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SCRIPTS = Path(__file__).resolve().parents[1]
central = load("central_publication", SCRIPTS / "central-publication.py")
signing = load("central_signing_fixture", SCRIPTS / "maven-signing.py")
fixtures = load("central_maven_fixture", SCRIPTS / "tests/maven-inventory-test.py")
IDENTIFIER = "11111111-2222-4333-8444-555555555555"
NAME = "synthetic-deployment"


class Response(io.BytesIO):
    def __init__(self, body, status=200):
        super().__init__(body)
        self.status = status


class Opener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def status(state="VALIDATED", **changes):
    return Response(json.dumps({"deploymentId": IDENTIFIER, "deploymentName": NAME, "deploymentState": state, **changes}).encode())


class ProtocolTests(unittest.TestCase):
    def test_upload_is_fixed_origin_user_managed_multipart_with_private_header(self):
        opener = Opener(Response(IDENTIFIER.encode(), 201))
        client = central.Client("synthetic-user", "synthetic-sensitive-password", opener)
        self.assertEqual(client.upload(b"synthetic bundle", NAME), IDENTIFIER)
        request = opener.requests[0]
        self.assertTrue(request.full_url.startswith("https://central.sonatype.com/api/v1/publisher/upload?"))
        self.assertIn("publishingType=USER_MANAGED", request.full_url)
        self.assertNotIn("AUTOMATIC", request.full_url)
        self.assertNotIn("synthetic-sensitive-password", request.full_url)
        self.assertIn(b'name="bundle"; filename="central-bundle.zip"', request.data)
        self.assertIn(b"synthetic bundle", request.data)
        self.assertTrue(request.get_header("Authorization").startswith("Bearer "))
        client.close()
        self.assertIsNone(client.authorization)

    def test_publish_requires_matching_validated_deployment_and_checks_before_post(self):
        opener = Opener(status(), Response(b"", 204))
        central.Client("fixture", "fixture", opener).publish(IDENTIFIER, NAME)
        self.assertIn("/status?id=", opener.requests[0].full_url)
        self.assertTrue(opener.requests[1].full_url.endswith("/deployment/" + IDENTIFIER))
        for state in ["PENDING", "VALIDATING", "PUBLISHING", "PUBLISHED", "FAILED"]:
            with self.subTest(state=state):
                blocked = Opener(status(state))
                with self.assertRaises(central.PublicationError):
                    central.Client("fixture", "fixture", blocked).publish(IDENTIFIER, NAME)
                self.assertEqual(len(blocked.requests), 1)

    def test_status_never_retains_upstream_error_text_or_unknown_state(self):
        opener = Opener(status(errors={"synthetic": ["untrusted echo synthetic-sensitive-password"]}))
        result = central.Client("fixture", "fixture", opener).status(IDENTIFIER, NAME)
        self.assertEqual(result["errorGroups"], 1)
        self.assertNotIn("synthetic-sensitive-password", json.dumps(result))
        for response in [status(deploymentName="wrong"), status(deploymentId="bad"), status("UNKNOWN")]:
            with self.assertRaises(central.PublicationError):
                central.Client("fixture", "fixture", Opener(response)).status(IDENTIFIER, NAME)

    def test_redirects_throttles_and_transport_errors_have_no_retry_or_raw_body(self):
        for code in [302, 401, 403, 429, 500]:
            error = urllib.error.HTTPError(central.ORIGIN, code, "synthetic-sensitive-password", {"Retry-After": "17"}, io.BytesIO(b"synthetic-sensitive-password"))
            opener = Opener(error)
            with self.assertRaises(central.ApiError) as caught:
                central.Client("fixture", "fixture", opener).upload(b"fixture", NAME)
            self.assertEqual(caught.exception.status, code)
            self.assertEqual(caught.exception.retry_after, 17)
            self.assertNotIn("synthetic-sensitive-password", str(caught.exception))
            self.assertEqual(len(opener.requests), 1)
        opener = Opener(urllib.error.URLError("synthetic-sensitive-password"))
        with self.assertRaises(central.ApiError) as caught:
            central.Client("fixture", "fixture", opener).upload(b"fixture", NAME)
        self.assertIsNone(caught.exception.status)
        self.assertEqual(len(opener.requests), 1)
        self.assertIsNone(central.NoRedirect().redirect_request(None, None, 302, "fixture", {}, "https://other.invalid"))

    def test_default_transport_disables_proxy_and_redirect_handlers(self):
        client = central.Client("fixture", "fixture")
        self.assertTrue(any(isinstance(handler, central.NoRedirect) for handler in client.opener.handlers))
        for handler in client.opener.handlers:
            if isinstance(handler, central.urllib.request.ProxyHandler):
                self.assertEqual(handler.proxies, {})
        client.close()

    def test_response_size_and_token_delimiters_fail_closed(self):
        with self.assertRaises(central.PublicationError):
            central.Client("fixture", "fixture", Opener(Response(b"x" * 65537))).status(IDENTIFIER, NAME)
        for username, password in [("", "fixture"), ("name:other", "fixture"), ("fixture", "secret\n")]:
            with self.assertRaises(central.PublicationError):
                central.Client(username, password)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.InventoryTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.repo.parent
        self.destination = self.root / "signed"
        self.manifest = self.fixture.run_check()
        self.manifest.update(head="1" * 40, tree="2" * 40, verified_unsigned_inventory_sha256=central.inventory.digest(self.manifest))
        class FakeCrypto:
            fingerprint = "A" * 40
            def sign(self, payload):
                return b"-----BEGIN PGP SIGNATURE-----\nsynthetic-offline-fixture\n-----END PGP SIGNATURE-----\n"
            def verify(self, signature, payload):
                pass
            def close(self):
                pass
        signing.stage_signed(self.fixture.repo, self.destination, self.manifest, FakeCrypto(), lambda: None)

    def test_bundle_revalidates_graph_and_hashes_but_never_claims_new_crypto(self):
        receipt, body = central.signed_bundle(self.destination, fixtures.META, fixtures.VERSION)
        self.assertEqual(len(receipt["artifacts"]), 28)
        self.assertEqual(hashlib.sha256(body).hexdigest(), receipt["bundle_sha256"])

    def test_tampered_bundle_or_consumer_receipt_is_refused_before_credentials(self):
        receipt_path = self.destination / "verification.json"
        receipt = json.loads(receipt_path.read_text())
        receipt["verified_unsigned_inventory_sha256"] = "0" * 64
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaises(central.PublicationError):
            central.signed_bundle(self.destination, fixtures.META, fixtures.VERSION)
        receipt["bundle_sha256"] = "0" * 64
        receipt_path.write_text(json.dumps(receipt))
        with self.assertRaises(central.PublicationError):
            central.signed_bundle(self.destination, fixtures.META, fixtures.VERSION)

    def test_bundle_links_and_unsigned_receipts_are_refused(self):
        path = self.destination / "verification.json"
        receipt = json.loads(path.read_text())
        receipt["signatures_verified"] = False
        path.write_text(json.dumps(receipt))
        with self.assertRaises(central.PublicationError):
            central.signed_bundle(self.destination, fixtures.META, fixtures.VERSION)
        alias = self.root / "linked"
        alias.symlink_to(self.destination, target_is_directory=True)
        with self.assertRaises(central.PublicationError):
            central.signed_bundle(alias, fixtures.META, fixtures.VERSION)

    def test_source_gate_requires_tag_accepted_main_and_successful_exact_ci(self):
        receipt = {"head": "1" * 40, "tree": "2" * 40}
        def output(command, **kwargs):
            if command[:3] == ["git", "rev-parse", "HEAD"] or command[:2] == ["git", "rev-parse"] and "refs/tags/" in command[-1]:
                return receipt["head"] + "\n"
            if command[:3] == ["git", "rev-parse", "HEAD^{tree}"]:
                return receipt["tree"] + "\n"
            if command[:2] == ["git", "status"]:
                return ""
            return json.dumps([{"headSha": receipt["head"], "event": "push", "status": "completed", "conclusion": "success"}])
        with patch.object(central.subprocess, "check_output", side_effect=output), patch.object(central.subprocess, "run") as run:
            central.release_source(self.root, receipt, fixtures.VERSION)
            self.assertIn("--is-ancestor", run.call_args.args[0])
        with patch.object(central.subprocess, "check_output", return_value="wrong"):
            with self.assertRaises(central.PublicationError):
                central.release_source(self.root, receipt, fixtures.VERSION)


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def operation(self, mode, client, identifier=None):
        receipt = {"head": "1" * 40, "tree": "2" * 40, "version": fixtures.VERSION, "bundle_sha256": "a" * 64}
        args = SimpleNamespace(mode=mode, deployment_id=identifier)
        with patch.object(central, "Client", return_value=client), patch.object(central.getpass, "getpass", return_value="synthetic-private-field"), contextlib.redirect_stdout(io.StringIO()):
            central.owner_operation(args, receipt, b"fixture", NAME, self.root)

    def journal(self, identifier=IDENTIFIER, operation="uploaded", **extra):
        record = {"name": NAME, "head": "1" * 40, "tree": "2" * 40, "version": fixtures.VERSION, "bundle_sha256": "a" * 64, "deploymentId": identifier, "operation": operation, **extra}
        central.write_receipt(self.root / "central-deployment.json", record)
        return record

    def test_publish_attempt_history_survives_stale_status_and_blocks_another_mutation(self):
        self.journal()
        client = SimpleNamespace(status=Mock(return_value={"deploymentId": IDENTIFIER, "deploymentState": "VALIDATED", "errorGroups": 0}), publish=Mock(side_effect=central.ApiError()), close=Mock())
        with self.assertRaises(central.ApiError):
            self.operation("publish", client)
        self.assertTrue(central.json_file(self.root / "central-deployment.json")["publishAttempted"])
        self.operation("status", client)
        record = central.json_file(self.root / "central-deployment.json")
        self.assertEqual(record["operation"], "publish-uncertain")
        self.assertTrue(record["publishAttempted"])
        with self.assertRaises(central.PublicationError):
            self.operation("publish", client)
        self.assertEqual(client.publish.call_count, 1)

    def test_known_id_is_immutable_and_missing_id_adoption_is_status_only(self):
        self.journal()
        client = SimpleNamespace(status=Mock(return_value={"deploymentId": IDENTIFIER, "deploymentState": "VALIDATED", "errorGroups": 0}), publish=Mock(), close=Mock())
        other = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        with self.assertRaises(central.PublicationError):
            self.operation("status", client, other)
        client.status.assert_not_called()
        self.assertEqual(central.json_file(self.root / "central-deployment.json")["deploymentId"], IDENTIFIER)
        self.journal(None, "upload-uncertain")
        with self.assertRaises(central.PublicationError):
            self.operation("publish", client, IDENTIFIER)
        client.publish.assert_not_called()
        self.operation("status", client, IDENTIFIER)
        self.assertEqual(central.json_file(self.root / "central-deployment.json")["deploymentId"], IDENTIFIER)

    def test_not_yet_validated_status_does_not_consume_a_publish_attempt(self):
        self.journal()
        client = SimpleNamespace(status=Mock(return_value={"deploymentId": IDENTIFIER, "deploymentState": "VALIDATING", "errorGroups": 0}), publish=Mock(), close=Mock())
        with self.assertRaises(central.PublicationError):
            self.operation("publish", client)
        client.publish.assert_not_called()
        self.assertNotIn("publishAttempted", central.json_file(self.root / "central-deployment.json"))

    def test_file_or_directory_sync_failure_prevents_upload_mutation(self):
        for fail_at in [1, 2]:
            with self.subTest(fail_at=fail_at):
                journal = self.root / (str(fail_at) + ".json")
                client = SimpleNamespace(upload=Mock(return_value=IDENTIFIER))
                calls = 0
                def sync(descriptor):
                    nonlocal calls
                    calls += 1
                    if calls == fail_at:
                        raise OSError("synthetic sync failure")
                with patch.object(central.os, "fsync", side_effect=sync):
                    with self.assertRaises(OSError):
                        central.upload_once(client, b"fixture", {"name": NAME}, journal)
                client.upload.assert_not_called()

    def test_pending_marker_is_synced_before_upload_and_publish(self):
        events = []
        original = central.os.fsync
        def sync(descriptor):
            events.append("sync")
            original(descriptor)
        def upload(*args):
            self.assertGreaterEqual(events.count("sync"), 2)
            events.append("upload")
            return IDENTIFIER
        with patch.object(central.os, "fsync", side_effect=sync):
            central.upload_once(SimpleNamespace(upload=upload), b"fixture", {"name": NAME}, self.root / "upload.json")
        self.journal()
        events.clear()
        def publish(*args):
            self.assertTrue(central.json_file(self.root / "central-deployment.json")["publishAttempted"])
            self.assertGreaterEqual(events.count("sync"), 4)
            events.append("publish")
        client = SimpleNamespace(status=Mock(return_value={"deploymentId": IDENTIFIER, "deploymentState": "VALIDATED", "errorGroups": 0}), publish=publish, close=Mock())
        with patch.object(central.os, "fsync", side_effect=sync):
            self.operation("publish", client)
        self.assertIn("publish", events)

    def test_uncertain_upload_is_persisted_and_cannot_be_blindly_repeated(self):
        client = SimpleNamespace(upload=lambda *args: (_ for _ in ()).throw(central.ApiError()))
        journal = self.root / "deployment.json"
        with self.assertRaises(central.ApiError):
            central.upload_once(client, b"fixture", {"name": NAME}, journal)
        self.assertEqual(json.loads(journal.read_text())["operation"], "upload-uncertain")
        self.assertEqual(journal.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(central.PublicationError):
            central.upload_once(client, b"fixture", {"name": NAME}, journal)

    def test_receipts_reject_symlinks_and_shared_worktrees_serialize(self):
        common = self.root / "common"
        common.mkdir()
        with patch.object(central.subprocess, "check_output", return_value=str(common)):
            first = central.shared_store(self.root / "worktree-one", fixtures.VERSION)
            second = central.shared_store(self.root / "worktree-two", fixtures.VERSION)
        self.assertEqual(first, second)
        with central.exclusive_operation(first):
            with self.assertRaises(FileExistsError):
                with central.exclusive_operation(second):
                    self.fail("duplicate operation admitted")
        target = self.root / "untouched"
        target.write_text("preserve")
        alias = first / "receipt.json"
        alias.symlink_to(target)
        with self.assertRaises(central.PublicationError):
            central.write_receipt(alias, {"state": "fixture"})
        self.assertEqual(target.read_text(), "preserve")

    def test_successful_upload_records_only_public_id_and_hash_metadata(self):
        journal = self.root / "deployment.json"
        result = central.upload_once(SimpleNamespace(upload=lambda *args: IDENTIFIER), b"fixture", {"name": NAME, "bundle_sha256": "a" * 64}, journal)
        self.assertEqual(result["deploymentId"], IDENTIFIER)
        self.assertEqual(result["operation"], "uploaded")
        self.assertNotIn("authorization", journal.read_text().lower())

    def test_first_use_directory_ancestors_are_synced_before_operation_admission(self):
        common = self.root / "common"
        common.mkdir()
        events = []
        original = central.sync_directory
        def sync(path):
            events.append(path)
            original(path)
        with patch.object(central.subprocess, "check_output", return_value=str(common)), patch.object(central, "sync_directory", side_effect=sync):
            store = central.shared_store(self.root, fixtures.VERSION)
        self.assertEqual(events, [common, common / "uac-release-state"])
        self.assertEqual(store, common / "uac-release-state" / fixtures.VERSION)
        other = self.root / "failed-common"
        other.mkdir()
        with patch.object(central, "Client") as client, patch.object(central.subprocess, "check_output", return_value=str(other)), patch.object(central, "sync_directory", side_effect=OSError("synthetic parent sync failure")):
            with self.assertRaises(OSError):
                central.owner_operation(SimpleNamespace(mode="upload"), {}, b"fixture", NAME, central.shared_store(self.root, fixtures.VERSION))
            client.assert_not_called()


if __name__ == "__main__":
    unittest.main()
