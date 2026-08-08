"""Tests for MessagingService's file/image/audio attachment support
(FIELD_FILE_ATTACHMENTS/FIELD_IMAGE — see messaging.py's own doc
comments for the real wire shapes, verified against Sideband's source).

Same scope discipline as test_messaging.py: only the synchronous prefix
of _send() (attachment save + entry construction) is exercised here —
threading.Thread is stubbed so the real RNS/LXMF delivery thread never
starts, and building the actual LXMF fields dict happens inside that
stubbed-out thread, out of scope for a unit test.
"""

import os
import types

import pytest

from nomadnet_web.messaging import (
    MAX_ATTACHMENT_BYTES,
    MessagingService,
    _sanitize_attachment_filename,
)

DEST_HASH = "bb" * 16


class _StubMessageStore:
    def __init__(self):
        self.saved = []

    def save_sent(self, entry):
        self.saved.append(entry)


class _NoOpThread:
    def __init__(self, target=None, daemon=None, **kwargs):
        self.target = target

    def start(self):
        pass


@pytest.fixture(autouse=True)
def _no_background_delivery(monkeypatch):
    monkeypatch.setattr("nomadnet_web.messaging.threading.Thread", _NoOpThread)


@pytest.fixture
def service(tmp_path):
    svc = MessagingService(storage_path=str(tmp_path), message_store=_StubMessageStore())
    svc._get_user_router = lambda user_sub: {
        "dest":   types.SimpleNamespace(hexhash=DEST_HASH),
        "router": types.SimpleNamespace(),
    }
    return svc


def test_sanitize_filename_strips_path_traversal():
    assert _sanitize_attachment_filename("../../etc/passwd") == "passwd"
    assert _sanitize_attachment_filename("..\\..\\evil.exe") == "evil.exe"
    assert _sanitize_attachment_filename("normal.txt") == "normal.txt"


def test_sanitize_filename_falls_back_for_empty_input():
    assert _sanitize_attachment_filename("") == "attachment"
    assert _sanitize_attachment_filename(None) == "attachment"


def test_send_with_file_attachment_writes_real_file_and_metadata(service):
    ok, msg_id = service.send_message(
        DEST_HASH, "here's the doc", user_sub="u1",
        attachment_filename="report.txt",
        attachment_data=b"hello world",
        attachment_kind="file",
    )
    assert ok

    entry = service._msg_store.saved[0]
    attachment = entry["attachment"]
    assert attachment is not None
    assert attachment["kind"] == "file"
    assert attachment["filename"] == "report.txt"
    assert attachment["size"] == len(b"hello world")
    assert os.path.exists(attachment["path"])
    with open(attachment["path"], "rb") as fh:
        assert fh.read() == b"hello world"


def test_send_with_image_attachment_derives_mime_from_format(service):
    ok, _ = service.send_message(
        DEST_HASH, "", user_sub="u1",
        attachment_filename="photo.webp",
        attachment_data=b"\x00\x01\x02fake-webp-bytes",
        attachment_kind="image",
        image_format="webp",
    )
    assert ok

    attachment = service._msg_store.saved[0]["attachment"]
    assert attachment["kind"] == "image"
    assert attachment["mime"] == "image/webp"


def test_send_without_attachment_stores_none(service):
    ok, _ = service.send_message(DEST_HASH, "just text", user_sub="u1")
    assert ok
    assert service._msg_store.saved[0]["attachment"] is None


def test_send_rejects_oversized_attachment(service):
    oversized = b"x" * (MAX_ATTACHMENT_BYTES + 1)
    ok, message = service.send_message(
        DEST_HASH, "too big", user_sub="u1",
        attachment_filename="huge.bin",
        attachment_data=oversized,
        attachment_kind="file",
    )
    assert not ok
    assert "too large" in message.lower()
    # Rejected before ever reaching save_sent — no partial/orphaned entry.
    assert service._msg_store.saved == []


def test_attachment_filenames_are_sanitized_on_disk(service):
    ok, _ = service.send_message(
        DEST_HASH, "", user_sub="u1",
        attachment_filename="../../../etc/passwd",
        attachment_data=b"not actually passwd",
        attachment_kind="file",
    )
    assert ok
    attachment = service._msg_store.saved[0]["attachment"]
    assert attachment["filename"] == "passwd"
    assert ".." not in attachment["path"]
