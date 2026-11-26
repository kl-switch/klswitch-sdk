"""Listing the middleware twice must stop the app booting.

Django runs every MIDDLEWARE entry, so a duplicate evaluates each request once
per entry — double latency and two analytics events, meaning decision counts
read multiplied. Nothing about the responses looks wrong, so the only place to
catch it is configuration time.
"""
from __future__ import annotations

import pytest

from klswitch.adapters.django import _reject_duplicate_middleware

MW = "klswitch.adapters.django.KlswitchMiddleware"


class _Settings:
    def __init__(self, middleware: list[str]) -> None:
        self.MIDDLEWARE = middleware


def test_single_entry_boots() -> None:
    _reject_duplicate_middleware(_Settings([MW, "django.middleware.common.CommonMiddleware"]))


def test_duplicate_entry_is_refused() -> None:
    from django.core.exceptions import ImproperlyConfigured

    with pytest.raises(ImproperlyConfigured, match="appears 2 times"):
        _reject_duplicate_middleware(_Settings([MW, MW]))


def test_a_subclassed_duplicate_is_also_refused() -> None:
    """Subclassing to preset config is a normal Django idiom, so a plain string
    compare against the shipped path would miss the duplicate."""
    from django.core.exceptions import ImproperlyConfigured

    with pytest.raises(ImproperlyConfigured, match="appears 2 times"):
        _reject_duplicate_middleware(_Settings([MW, "myapp.middleware.KlswitchMiddleware"]))


def test_absent_middleware_is_fine() -> None:
    _reject_duplicate_middleware(_Settings(["django.middleware.common.CommonMiddleware"]))
