"""Tests for SiteServer's NomadPortal-Android-specific hardening: no
executable pages, ever (see site_server.py's own module doc comment for
why — a phone-hosted site is a different trust boundary than the
original desktop tool this was ported from), plus stop()'s cleanup.

Scoped the same way as this project's other RNS-adjacent tests: only
the parts that don't require a real running RNS.Reticulum() instance
are exercised directly. `fetch_page`/`_serve_page` are pure filesystem
operations (no `self._dest` needed) — real hardening-relevant logic
that's meaningful to test without a network. `stop()`'s deregistration
is tested against a stub destination object, not a real one.
"""

import os
import stat
import sys

import pytest

from nomadnet_web.site_server import SiteServer


class _StubDestination:
    """Records deregister_request_handler calls; nothing else exercised."""

    def __init__(self):
        self.deregistered = []

    def deregister_request_handler(self, path):
        self.deregistered.append(path)
        return True


@pytest.fixture
def server(tmp_path):
    return SiteServer(
        pages_dir=str(tmp_path / "pages"),
        files_dir=str(tmp_path / "files"),
        identity_file=str(tmp_path / "site_identity.id"),
    )


def _make_executable_page(server, tmp_path, name="run.mu", content="#!/bin/sh\necho pwned\n"):
    os.makedirs(server._pages_dir, exist_ok=True)
    page_path = os.path.join(server._pages_dir, name)
    with open(page_path, "w") as fh:
        fh.write(content)
    # Executable bit set — this is exactly the condition the original
    # (pre-hardening) code branched on to run the file as a subprocess.
    os.chmod(page_path, os.stat(page_path).st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    return page_path


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX executable bit doesn't apply on Windows")
def test_fetch_page_never_executes_even_when_executable_bit_is_set(server, tmp_path):
    page_path = _make_executable_page(server, tmp_path)
    assert os.access(page_path, os.X_OK)  # sanity: the bit really is set

    content, error = server.fetch_page("/run.mu")

    assert error is None
    # The raw script text comes back verbatim — it was never executed
    # (an execution would have returned "pwned\n", not the shebang/echo
    # source itself).
    assert content == b"#!/bin/sh\necho pwned\n"
    assert b"pwned\n" != content


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX executable bit doesn't apply on Windows")
def test_serve_page_never_executes_even_when_executable_bit_is_set(server, tmp_path):
    _make_executable_page(server, tmp_path)

    result = server._serve_page(
        "/page/run.mu", data=None, request_id=None, link_id=None,
        remote_identity=None, requested_at=0,
    )

    assert result == b"#!/bin/sh\necho pwned\n"


def test_fetch_page_rejects_path_traversal(server, tmp_path):
    os.makedirs(server._pages_dir, exist_ok=True)
    secret = tmp_path / "secret.txt"
    secret.write_text("not for browsing")

    content, error = server.fetch_page("/../secret.txt")

    assert content is None
    assert error is not None


def test_fetch_page_not_found(server):
    content, error = server.fetch_page("/nope.mu")
    assert content is None
    assert "not found" in error.lower()


def test_stop_deregisters_every_registered_path(server):
    stub_dest = _StubDestination()
    server._dest = stub_dest
    server._registered_paths = {"/page/index.mu", "/page/about.mu", "/file/readme.txt"}
    server._running = True

    server.stop()

    assert server._running is False
    assert sorted(stub_dest.deregistered) == ["/file/readme.txt", "/page/about.mu", "/page/index.mu"]
    assert server._registered_paths == set()


def test_stop_is_safe_when_never_started(server):
    # No self._dest set at all — shouldn't raise.
    server.stop()
    assert server._running is False
