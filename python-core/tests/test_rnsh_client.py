"""Tests for RnshSession's message-handling/state logic.

`_connect()` itself does real RNS network work (path discovery, link
establishment) once started — same "not something a unit test should
exercise" reasoning as messaging.py's own `_deliver()` background
thread (see that file's own top doc comment). These tests instead
drive `_on_message`/`status`/`read_output`/`send_input`/`resize`
directly against a session that's never actually connected — the real,
testable "what happens when this message/state occurs" logic, using
real (not stubbed) message classes from `_register_message_types()`,
since `RNS` is genuinely importable in this test environment (pure
local bookkeeping to construct a message instance, no network needed).
"""

from nomadnet_web.rnsh_client import RnshSession, _register_message_types

DEST_HASH = "aa" * 16


def _make_session():
    session = RnshSession(identity=object(), destination_hash_hex=DEST_HASH)
    session._msg = _register_message_types()
    return session


def test_initial_state_is_connecting():
    session = _make_session()
    status = session.status()
    assert status["state"] == RnshSession.STATE_CONNECTING
    assert status["error"] is None
    assert status["exit_code"] is None


def test_on_message_version_info_sets_ok_and_signals_event():
    session = _make_session()
    msg = session._msg["VersionInfo"](sw_version="rnsh-test")

    handled = session._on_message(msg)

    assert handled is True
    assert session._version_ok is True
    assert session._version_event.is_set()


def test_on_message_stream_data_buffers_output():
    session = _make_session()
    msg = session._msg["StreamData"](session._msg["StreamData"].STREAM_ID_STDOUT, b"hello\n", False, False)

    session._on_message(msg)

    assert session.read_output() == b"hello\n"


def test_on_message_stream_data_accumulates_across_multiple_messages():
    session = _make_session()
    stream_cls = session._msg["StreamData"]
    session._on_message(stream_cls(stream_cls.STREAM_ID_STDOUT, b"foo", False, False))
    session._on_message(stream_cls(stream_cls.STREAM_ID_STDERR, b"bar", False, False))

    assert session.read_output() == b"foobar"


def test_read_output_drains_and_clears_buffer():
    session = _make_session()
    stream_cls = session._msg["StreamData"]
    session._on_message(stream_cls(stream_cls.STREAM_ID_STDOUT, b"data", False, False))

    first = session.read_output()
    second = session.read_output()

    assert first == b"data"
    assert second == b""


def test_on_message_command_exited_closes_session_with_exit_code():
    session = _make_session()
    session._state = RnshSession.STATE_CONNECTED
    msg = session._msg["CommandExited"](return_code=7)

    session._on_message(msg)

    status = session.status()
    assert status["state"] == RnshSession.STATE_CLOSED
    assert status["exit_code"] == 7


def test_on_message_command_exited_defaults_none_return_code_to_zero():
    session = _make_session()
    session._state = RnshSession.STATE_CONNECTED
    msg = session._msg["CommandExited"](return_code=None)

    session._on_message(msg)

    assert session.status()["exit_code"] == 0


def test_on_message_error_sets_failed_state_with_message():
    session = _make_session()
    msg = session._msg["Error"](msg="listener rejected connection", fatal=False)

    session._on_message(msg)

    status = session.status()
    assert status["state"] == RnshSession.STATE_FAILED
    assert status["error"] == "listener rejected connection"


def test_send_input_is_a_noop_before_connected():
    session = _make_session()
    # No channel set yet (never went through _connect()) — must not raise.
    session.send_input(b"echo hi\n")


def test_resize_is_a_noop_before_connected():
    session = _make_session()
    session.resize(24, 80)


def test_disconnect_sets_closed_state_with_no_link():
    session = _make_session()
    session.disconnect()
    assert session.status()["state"] == RnshSession.STATE_CLOSED


def test_disconnect_does_not_override_failed_state():
    session = _make_session()
    session._fail("some real failure")
    session.disconnect()
    assert session.status()["state"] == RnshSession.STATE_FAILED
