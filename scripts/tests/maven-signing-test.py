#!/usr/bin/env python3
"""Offline signing-boundary fixtures. Never generates a key or calls real GnuPG."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("maven_signing", Path(__file__).resolve().parents[1] / "maven-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)
inventory_spec = importlib.util.spec_from_file_location("signing_inventory", Path(__file__).resolve().parents[1] / "maven-inventory.py")
inventory = importlib.util.module_from_spec(inventory_spec)
inventory_spec.loader.exec_module(inventory)
FINGERPRINT = "A" * 40
IDENTITY = "Release Fixture <release@example.invalid>"


class FakeSigner:
    fingerprint = FINGERPRINT

    def __init__(self, failure=None):
        self.failure, self.signed, self.verified, self.closes = failure, [], [], 0
        self.closed = False

    def check_identity(self):
        pass

    def sign(self, payload):
        if self.failure == "sign":
            raise signing.SigningError("synthetic signing failure")
        self.signed.append(payload.read_bytes())
        return b"synthetic-offline-signature:" + hashlib.sha512(payload.read_bytes()).hexdigest().encode()

    def verify(self, signature, payload):
        if self.failure == "verify":
            raise signing.SigningError("synthetic signature rejection")
        assert signature.read_bytes() == b"synthetic-offline-signature:" + hashlib.sha512(payload.read_bytes()).hexdigest().encode()
        self.verified.append(payload.name)

    def close(self):
        if self.closed:
            return
        self.closed = True
        self.closes += 1
        if self.failure == "close":
            raise signing.SigningError("synthetic cleanup failure")


class SigningTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.repository = self.root / "unsigned"
        self.destination = self.root / "signed"
        self.repository.mkdir()
        artifacts = []
        for n in range(28):
            path = self.repository / "com/myadidi/fixture/0.1.0-alpha.0.p8.1" / (str(n) + ".jar")
            path.parent.mkdir(parents=True, exist_ok=True)
            body = ("synthetic fixture " + str(n)).encode()
            path.write_bytes(body)
            for algorithm in signing.HASHES:
                path.with_name(path.name + "." + algorithm).write_text(hashlib.new(algorithm, body).hexdigest() + "\n")
            artifacts.append({"path": path.relative_to(self.repository).as_posix(), "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
        self.manifest = {"head": "1" * 40, "tree": "2" * 40, "verified_unsigned_inventory_sha256": "3" * 64, "version": "0.1.0-alpha.0.p8.1", "group": "com.myadidi", "publications": ["synthetic"], "artifacts": artifacts}

    def stage(self, signer=None, final_check=lambda: None):
        return signing.stage_signed(self.repository, self.destination, self.manifest, signer or FakeSigner(), final_check)

    def assert_rejected(self, signer, final_check=lambda: None):
        with self.assertRaises((signing.SigningError, OSError)):
            self.stage(signer, final_check)
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.root.glob(".signed-maven-*")))

    def test_signs_exact_copies_and_checks_all_signatures_without_changing_inputs(self):
        before = {str(p.relative_to(self.repository)): p.read_bytes() for p in self.repository.rglob("*") if p.is_file()}
        signer = FakeSigner()
        receipt = self.stage(signer)
        self.assertEqual((len(signer.signed), len(signer.verified), signer.closes), (28, 28, 1))
        self.assertEqual(before, {str(p.relative_to(self.repository)): p.read_bytes() for p in self.repository.rglob("*") if p.is_file()})
        self.assertTrue(receipt["signatures_verified"])
        self.assertFalse(receipt["uploaded"])
        self.assertFalse(receipt["remote_distribution_verified"])
        self.assertEqual(receipt["head"], self.manifest["head"])
        with zipfile.ZipFile(self.destination / "central-bundle.zip") as archive:
            self.assertEqual(len(archive.namelist()), 280)
            self.assertNotIn("verification.json", archive.namelist())
            self.assertEqual(sum(n.endswith(".asc") for n in archive.namelist()), 28)
        self.assertEqual(json.loads((self.destination / "verification.json").read_text()), receipt)

    def test_tampered_unsigned_payload_stops_before_signing(self):
        (self.repository / self.manifest["artifacts"][0]["path"]).write_bytes(b"tampered")
        signer = FakeSigner()
        self.assert_rejected(signer)
        self.assertFalse(signer.signed)

    def test_tampered_checksum_stops_before_signing(self):
        path = self.repository / self.manifest["artifacts"][0]["path"]
        path.with_name(path.name + ".sha256").write_text("incorrect")
        signer = FakeSigner()
        self.assert_rejected(signer)
        self.assertFalse(signer.signed)

    def test_failed_sign_verify_and_cleanup_leave_no_success_output(self):
        for failure in ["sign", "verify", "close"]:
            with self.subTest(failure=failure):
                self.assert_rejected(FakeSigner(failure))

    def test_source_change_after_signing_invalidates_whole_result(self):
        def changed():
            raise signing.SigningError("synthetic source changed")
        self.assert_rejected(FakeSigner(), changed)

    def test_existing_output_is_not_overwritten(self):
        self.destination.mkdir()
        marker = self.destination / "preserve"
        marker.write_text("original")
        with self.assertRaises(signing.SigningError):
            self.stage()
        self.assertEqual(marker.read_text(), "original")

    def test_repository_and_nested_directory_links_are_refused(self):
        actual = self.repository / "com"
        moved = self.root / "moved"
        actual.rename(moved)
        actual.symlink_to(moved, target_is_directory=True)
        with self.assertRaises(signing.SigningError):
            self.stage()
        actual.unlink()
        moved.rename(actual)
        alias = self.root / "alias"
        alias.symlink_to(self.repository, target_is_directory=True)
        with self.assertRaises(signing.SigningError):
            signing.stage_signed(alias, self.destination, self.manifest, FakeSigner(), lambda: None)

    def test_incomplete_duplicate_and_escaping_inventories_are_refused(self):
        original = self.manifest["artifacts"]
        for bad in [original[:27], original[:27] + original[:1], [{**original[0], "path": "../escape"}] + original[1:]]:
            self.manifest["artifacts"] = bad
            self.assert_rejected(FakeSigner())
        self.manifest["artifacts"] = original

    def test_validsig_requires_the_approved_key_rsa_v4_sha512_and_binary_signature(self):
        status = "[GNUPG:] VALIDSIG " + FINGERPRINT + " 2026-11-02 1793620000 0 4 0 1 10 00 " + FINGERPRINT + "\n"
        signing.valid_signature(status, FINGERPRINT)
        for bad in [status.replace(FINGERPRINT, "B" * 40), status.replace(" 4 0 1 10 00 ", " 4 0 1 8 00 "), status.replace(" 4 0 1 10 00 ", " 6 0 1 10 00 "), status * 2, status + "[GNUPG:] EXPKEYSIG synthetic\n", "[GNUPG:] GOODSIG synthetic\n"]:
            with self.assertRaises(signing.SigningError):
                signing.valid_signature(bad, FINGERPRINT)

    def test_key_listing_rejects_other_identity_expiration_or_extra_keys(self):
        expiry = str(int(time.time()) + 3600)
        created = str(int(time.time()) - 3600)
        listing = "pub:u:4096:1:AAAAAAAAAAAAAAAA:" + created + ":" + expiry + "::u:::scSC:\nfpr:::::::::" + FINGERPRINT + ":\nuid:u::::::::" + IDENTITY + ":\n"
        signing.validate_key_listing(listing, FINGERPRINT, IDENTITY, int(expiry))
        for bad in [listing.replace(IDENTITY, "Someone else"), listing.replace(":4096:", ":2048:"), listing.replace(":" + expiry + ":", ":1:"), listing.replace("pub:u:", "pub:i:"), listing.replace("scSC:", "scSCD:"), listing + listing, listing + "sub:u:4096:1:synthetic:\n"]:
            with self.assertRaises(signing.SigningError):
                signing.validate_key_listing(bad, FINGERPRINT, IDENTITY, int(expiry))

    def test_private_phrase_uses_stdin_never_argv_and_cleanup_is_home_scoped(self):
        phrase = "synthetic-private-passphrase"
        signer = signing.GpgSigner(self.root / "gnupg", FINGERPRINT, phrase, {"PATH": "/fixture", "GNUPGHOME": "/wrong"}, IDENTITY, int(time.time()) + 3600)
        signature = b"-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n"
        with patch.object(signing.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, signature)
            self.assertEqual(signer.sign(self.root / "payload"), signature)
            args, kwargs = run.call_args
            self.assertNotIn(phrase, args[0])
            self.assertEqual(kwargs["input"], (phrase + "\n").encode())
            self.assertNotIn("GNUPGHOME", kwargs["env"])
            self.assertIn("--no-auto-key-retrieve", args[0])
            self.assertNotIn("--export-secret-keys", args[0])
            signer.close()
            self.assertEqual(run.call_args.args[0], [str(signing.GPGCONF), "--homedir", str(self.root / "gnupg"), "--kill", "gpg-agent"])
            self.assertIsNone(signer.phrase)

    def test_owner_lock_serializes_and_refuses_existing_or_linked_lock(self):
        lock = self.root / ".signing-owner.lock"
        with signing.owner_lock(self.root):
            self.assertEqual(lock.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                with signing.owner_lock(self.root):
                    self.fail("second signing session admitted")
        self.assertFalse(lock.exists())
        marker = self.root / "preserve"
        marker.write_text("untouched")
        lock.symlink_to(marker)
        with self.assertRaises(FileExistsError):
            with signing.owner_lock(self.root):
                self.fail("linked lock admitted")
        self.assertEqual(marker.read_text(), "untouched")

    def test_distinct_worktree_outputs_share_one_owner_key_lock_before_any_gpg_use(self):
        store = self.root / "shared-signing-store"
        store.mkdir(mode=0o700)
        other_output = self.root / "other-worktree-signed-output"
        other = FakeSigner()
        factories = []
        def second_factory():
            factories.append("started")
            return other
        first = FakeSigner()
        original_sign = first.sign
        attempted = False
        def sign(payload):
            nonlocal attempted
            if not attempted:
                attempted = True
                with self.assertRaises(FileExistsError):
                    signing.owner_sign(store, self.repository, other_output, self.manifest, second_factory, lambda: None)
                self.assertFalse(factories)
                self.assertEqual(other.closes, 0)
            return original_sign(payload)
        first.sign = sign
        signing.owner_sign(store, self.repository, self.destination, self.manifest, lambda: first, lambda: None)
        self.assertEqual(first.closes, 1)
        self.assertFalse(other_output.exists())
        signing.owner_sign(store, self.repository, other_output, self.manifest, second_factory, lambda: None)
        self.assertEqual(factories, ["started"])
        self.assertEqual(other.closes, 1)

    def test_same_head_rebuilt_inventory_cannot_reuse_previous_consumer_acceptance(self):
        manifest = {k: v for k, v in self.manifest.items() if k not in {"head", "tree", "verified_unsigned_inventory_sha256"}}
        head = "1" * 40
        previous = {"head": head, "version": manifest["version"], "mode": "unsigned-local", "working_tree_dirty": False, "inventory_sha256": inventory.digest(manifest), "stages": dict.fromkeys(["metadata", "producer", "jvm-consumer", "android-consumer", "native-consumer"], "passed")}
        signing.require_verified_inventory(previous, manifest, inventory, head)
        changed = json.loads(json.dumps(manifest))
        changed["artifacts"][0]["sha256"] = "B" * 64
        for bad in [changed, {**manifest, "version": "0.1.0-alpha.0.p8.2"}]:
            with self.assertRaises(signing.SigningError):
                signing.require_verified_inventory(previous, bad, inventory, head)
        with self.assertRaises(signing.SigningError):
            signing.require_verified_inventory({k: v for k, v in previous.items() if k != "inventory_sha256"}, manifest, inventory, head)

    def test_public_setup_reads_only_protected_receipt_and_rejects_links_or_bad_identity(self):
        parent = self.root / "owner"
        store = parent / "signing"
        for path in [parent, store, store / "gnupg"]:
            path.mkdir(mode=0o700)
        receipt = store / "setup-complete.json"
        record = {"identity": IDENTITY, "fingerprint": FINGERPRINT, "expiresAtUnix": int(time.time()) + 3600, "openPGPVersion": 4, "protectedBackupPacketFormatVerified": True, "dedicatedAgentStopCommandPassed": True}
        receipt.write_text(json.dumps(record))
        receipt.chmod(0o600)
        self.assertEqual(signing.public_setup(store)["fingerprint"], FINGERPRINT)
        for bad in [[], {**record, "identity": "wrong"}, {**record, "fingerprint": None}, {**record, "expiresAtUnix": 1}]:
            receipt.write_text(json.dumps(bad))
            with self.assertRaises(signing.SigningError):
                signing.public_setup(store)
        receipt.write_text(json.dumps(record))
        receipt.chmod(0o644)
        with self.assertRaises(signing.SigningError):
            signing.public_setup(store)
        receipt.chmod(0o600)
        alias = self.root / "linked-owner"
        alias.symlink_to(parent, target_is_directory=True)
        with self.assertRaises(signing.SigningError):
            signing.public_setup(alias / "signing")

    def test_agent_failure_and_timeout_are_explicit_without_retaining_passphrase(self):
        for failure in [subprocess.CompletedProcess([], 1), subprocess.TimeoutExpired("synthetic-command", 15)]:
            signer = signing.GpgSigner(self.root / "gnupg", FINGERPRINT, "synthetic-private-passphrase", {}, IDENTITY, int(time.time()) + 3600)
            kwargs = {"side_effect": failure} if isinstance(failure, Exception) else {"return_value": failure}
            with patch.object(signing.subprocess, "run", **kwargs):
                with self.assertRaises(signing.CleanupError):
                    signer.close()
            self.assertIsNone(signer.phrase)

    def test_noninteractive_main_refuses_before_key_or_passphrase_access(self):
        with patch.object(signing.sys.stdin, "isatty", return_value=False), patch.object(signing.getpass, "getpass") as prompt, patch.object(signing, "public_setup") as setup:
            with self.assertRaises(signing.SigningError):
                signing.main(self.root, None, {})
            prompt.assert_not_called()
            setup.assert_not_called()


if __name__ == "__main__":
    unittest.main()
