"""URL derivation from the config's ``base_url`` (with per-endpoint
overrides as escape hatches for self-hosted setups).

Centralised so the polling manager and
analytics reporter (A3) all build their URLs identically.
"""

from __future__ import annotations

from dataclasses import dataclass

DEFAULT_BASE_URL = "https://api.klswitch.dev"


@dataclass(frozen=True)
class ResolvedUrls:
    """Resolved data-plane endpoint URLs."""

    rules: str
    decisions: str
    manifest: str


def resolve_urls(
    *,
    base_url: str | None,
    rules_url: str | None,
    decisions_url: str | None,
    manifest_url: str | None = None,
) -> ResolvedUrls:
    """Pick the URL for each endpoint, preferring an explicit override
    when set, otherwise deriving from ``base_url`` (which itself
    defaults to :data:`DEFAULT_BASE_URL`).
    """
    base = (base_url or DEFAULT_BASE_URL).rstrip("/")
    return ResolvedUrls(
        rules=rules_url or f"{base}/v1/ruleset",
        decisions=decisions_url or f"{base}/v1/analytics",
        manifest=manifest_url or f"{base}/v1/manifest",
    )
