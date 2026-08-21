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


class _StubIdentity:
    """Minimal stand-in for RNS.Identity -- only .hash is ever read by
    _record_view's own self-visit exclusion check."""

    def __init__(self, hash_hex: str):
        self.hash = bytes.fromhex(hash_hex)


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


class TestViewCounter:
    """Real per-request view counting (per explicit direction — "are we
    able to set up a view counter for our hosted node"), not a
    synthetic/estimated figure."""

    def _make_page(self, server, name="index.mu", content=">Hello"):
        os.makedirs(server._pages_dir, exist_ok=True)
        with open(os.path.join(server._pages_dir, name), "w") as fh:
            fh.write(content)

    def test_serving_a_real_page_counts_as_a_view(self, server):
        self._make_page(server)
        server._serve_page("/page/index.mu", None, None, None, None, 0)
        assert server.total_views() == 1
        assert server.page_views() == {"/page/index.mu": 1}

    def test_a_missing_page_does_not_count_as_a_view(self, server):
        server._serve_page("/page/nope.mu", None, None, None, None, 0)
        assert server.total_views() == 0

    def test_repeated_requests_accumulate(self, server):
        self._make_page(server)
        for _ in range(3):
            server._serve_page("/page/index.mu", None, None, None, None, 0)
        assert server.total_views() == 3

    def test_serving_a_file_counts_as_a_view(self, server, tmp_path):
        os.makedirs(server._files_dir, exist_ok=True)
        with open(os.path.join(server._files_dir, "photo.jpg"), "wb") as fh:
            fh.write(b"fake-jpeg-bytes")
        server._serve_file("/file/photo.jpg", None, None, None, None, 0)
        assert server.total_views() == 1

    def test_default_synthetic_index_counts_as_a_view(self, server):
        # No real index.mu on disk -- _serve_default_index is what
        # _register_pages falls back to registering in that case (see
        # its own doc comment), and it always succeeds.
        server._serve_default_index("/page/index.mu", None, None, None, None, 0)
        assert server.total_views() == 1

    def test_page_views_returned_copy_does_not_mutate_real_state(self, server):
        self._make_page(server)
        server._serve_page("/page/index.mu", None, None, None, None, 0)
        snapshot = server.page_views()
        snapshot["/page/index.mu"] = 999
        assert server.total_views() == 1

    def test_no_stats_file_means_in_memory_only(self, server):
        # The `server` fixture doesn't pass stats_file -- real callers
        # that don't care about surviving a restart (or these tests
        # themselves) shouldn't need a real file on disk just to count.
        assert server._stats_file is None
        self._make_page(server)
        server._serve_page("/page/index.mu", None, None, None, None, 0)
        assert server.total_views() == 1

    def test_counts_persist_across_a_restart(self, tmp_path):
        stats_file = str(tmp_path / "view_stats.json")
        first = SiteServer(
            pages_dir=str(tmp_path / "pages"),
            files_dir=str(tmp_path / "files"),
            identity_file=str(tmp_path / "site_identity.id"),
            stats_file=stats_file,
        )
        self._make_page(first)
        first._serve_page("/page/index.mu", None, None, None, None, 0)
        first._serve_page("/page/index.mu", None, None, None, None, 0)
        assert first.total_views() == 2

        # A fresh instance, same stats_file -- simulates SiteServer being
        # reconstructed on the next hosting-enable or app restart.
        second = SiteServer(
            pages_dir=str(tmp_path / "pages"),
            files_dir=str(tmp_path / "files"),
            identity_file=str(tmp_path / "site_identity.id"),
            stats_file=stats_file,
        )
        assert second.total_views() == 2
        second._serve_page("/page/index.mu", None, None, None, None, 0)
        assert second.total_views() == 3

    def test_a_corrupt_stats_file_is_ignored_not_raised(self, tmp_path):
        stats_file = tmp_path / "view_stats.json"
        stats_file.write_text("not valid json{{{")
        server = SiteServer(
            pages_dir=str(tmp_path / "pages"),
            files_dir=str(tmp_path / "files"),
            identity_file=str(tmp_path / "site_identity.id"),
            stats_file=str(stats_file),
        )
        assert server.total_views() == 0


class TestViewCounterExcludesSelfVisits:
    """Real correction, not the original spec: "the view counter is for
    people visiting the node from outside" -- an identified request from
    this device's own known identity/identities must not count."""

    OWN_HASH = "11" * 16
    OTHER_HASH = "22" * 16

    def _make_page(self, server, name="index.mu", content=">Hello"):
        os.makedirs(server._pages_dir, exist_ok=True)
        with open(os.path.join(server._pages_dir, name), "w") as fh:
            fh.write(content)

    def _server(self, tmp_path):
        return SiteServer(
            pages_dir=str(tmp_path / "pages"),
            files_dir=str(tmp_path / "files"),
            identity_file=str(tmp_path / "site_identity.id"),
            own_identity_hashes={self.OWN_HASH},
        )

    def test_an_identified_self_request_does_not_count(self, tmp_path):
        server = self._server(tmp_path)
        self._make_page(server)
        server._serve_page("/page/index.mu", None, None, None, _StubIdentity(self.OWN_HASH), 0)
        assert server.total_views() == 0

    def test_an_identified_other_visitor_still_counts(self, tmp_path):
        server = self._server(tmp_path)
        self._make_page(server)
        server._serve_page("/page/index.mu", None, None, None, _StubIdentity(self.OTHER_HASH), 0)
        assert server.total_views() == 1

    def test_an_anonymous_request_still_counts(self, tmp_path):
        # No way to distinguish an anonymous self-visit from a real
        # anonymous outside visitor at this layer -- see own_identity_hashes'
        # own doc comment for why that's an accepted, honest limitation
        # (and why it's not reachable in practice today regardless).
        server = self._server(tmp_path)
        self._make_page(server)
        server._serve_page("/page/index.mu", None, None, None, None, 0)
        assert server.total_views() == 1

    def test_self_exclusion_applies_to_files_and_the_default_index_too(self, tmp_path):
        server = self._server(tmp_path)
        os.makedirs(server._files_dir, exist_ok=True)
        with open(os.path.join(server._files_dir, "photo.jpg"), "wb") as fh:
            fh.write(b"fake-jpeg-bytes")
        server._serve_file("/file/photo.jpg", None, None, None, _StubIdentity(self.OWN_HASH), 0)
        server._serve_default_index("/page/index.mu", None, None, None, _StubIdentity(self.OWN_HASH), 0)
        assert server.total_views() == 0

    def test_no_own_identity_hashes_means_nothing_is_excluded(self, server):
        # The plain `server` fixture (no own_identity_hashes passed) --
        # confirms this whole feature is additive, not a silent behavior
        # change for existing callers that don't opt in.
        self._make_page(server)
        server._serve_page("/page/index.mu", None, None, None, _StubIdentity(self.OWN_HASH), 0)
        assert server.total_views() == 1
