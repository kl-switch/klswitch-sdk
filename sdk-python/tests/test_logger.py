"""Logger smoke tests — exercise every method on NullLogger and the
stdlib adapter so the cosmetic "60% covered" gap on logger.py closes.
No assertions about side effects beyond "must not throw" for NullLogger;
the stdlib adapter gets a captured-handler round-trip.
"""

from __future__ import annotations

import logging

from klswitch.logger import NullLogger, StdlibLogger


def test_null_logger_every_level_is_silent() -> None:
    n = NullLogger()
    n.debug("ok")
    n.info("ok", {"rules_version": 1})
    n.warning("ok", {"error": "x"})
    n.error("ok", {"stack": "trace"})
    # fields-omitted overload path
    n.debug("no fields")
    n.info("no fields")
    n.warning("no fields")
    n.error("no fields")


def test_stdlib_logger_round_trips_via_handler() -> None:
    """StdlibLogger forwards to the standard ``logging`` module — capture
    via a handler attached to a fresh named logger so we don't pollute
    pytest's caplog or other tests.
    """
    pyLogger = logging.getLogger("klswitch.test.smoke")
    pyLogger.setLevel(logging.DEBUG)
    captured: list[logging.LogRecord] = []

    class _Capture(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            captured.append(record)

    handler = _Capture(level=logging.DEBUG)
    pyLogger.addHandler(handler)
    try:
        sl = StdlibLogger(pyLogger)
        sl.debug("debugmsg", {"rules_version": 1})
        sl.info("infomsg",   {"k": "v"})
        sl.warning("warnmsg", {"k": "v"})
        sl.error("errmsg",   {"k": "v"})
        # fields-omitted path exercises the `if fields:` False branch.
        sl.debug("d2")
        sl.info("i2")
        sl.warning("w2")
        sl.error("e2")

        # 8 records, one per call.
        assert len(captured) == 8
        msgs = [r.getMessage() for r in captured]
        assert "debugmsg" in msgs
        assert "infomsg" in msgs
        assert "warnmsg" in msgs
        assert "errmsg" in msgs
        # `extra={"klswitch": fields}` ends up on the record.
        record_with_fields = next(r for r in captured if r.getMessage() == "debugmsg")
        assert getattr(record_with_fields, "klswitch", None) == {"rules_version": 1}
    finally:
        pyLogger.removeHandler(handler)


def test_stdlib_logger_default_name_is_klswitch() -> None:
    """Constructed with no argument, StdlibLogger pulls
    ``logging.getLogger("klswitch")`` — verifies the default-arg branch.
    """
    sl = StdlibLogger()
    # Reach into the private member to assert the underlying name.
    assert sl._logger.name == "klswitch"
