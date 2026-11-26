"""
The regression: a host integrating without an adapter emitted FastAPI-style ``{id}`` templates.
Rules whose selectors carried no path parameter still matched, so the integration looked healthy
— only the parameterised rules silently missed. Six of eight checks passed, which is exactly the
shape that hides a broken integration.
"""
import pytest

from klswitch import build_evaluation_input, canonicalize_template


@pytest.mark.parametrize(
    "given,expected",
    [
        ("/api/items/{id}", "/api/items/:id"),
        ("/api/items/<id>", "/api/items/:id"),
        ("/api/items/<int:id>", "/api/items/:id"),
        ("/api/items/:id", "/api/items/:id"),
        ("/static/{file_path:path}", "/static/:file_path"),
        ("/static/<path:filename>", "/static/:filename"),
        ("/api/items/{id}/reviews/{reviewId}", "/api/items/:id/reviews/:reviewId"),
    ],
)
def test_every_dialect_canonicalises(given, expected):
    assert canonicalize_template(given) == expected


@pytest.mark.parametrize(
    "path", ["/api/items", "/api/payments/*/refund", "/api/items/**"]
)
def test_paths_without_parameters_are_untouched(path):
    assert canonicalize_template(path) == path


def test_build_evaluation_input_applies_it():
    # Being merely available was the old state: the per-dialect converters existed and the
    # caller had to know to find them.
    got = build_evaluation_input(method="post", path="/api/payments/{id}/refund")
    assert got.selector.keys["path"] == "/api/payments/:id/refund"
    assert got.selector.keys["method"] == "POST"
