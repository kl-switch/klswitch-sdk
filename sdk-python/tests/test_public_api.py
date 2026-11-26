"""The manual-integration surface is public API.

Frameworks without an adapter — Litestar, Tornado, aiohttp, Sanic, Falcon,
Bottle, Pyramid, Quart — integrate through these. They are re-exported from the
package root so the underscore-prefixed module path is not part of the contract
and can move.
"""
import klswitch


def test_manual_integration_helpers_are_exported():
    for name in (
        "build_evaluation_input",
        "flask_template_to_engine_path",
        "fastapi_template_to_engine_path",
    ):
        assert hasattr(klswitch, name), f"{name} must be importable from klswitch"
        assert name in klswitch.__all__, f"{name} must be in __all__"


def test_build_evaluation_input_normalises_like_the_adapters():
    got = klswitch.build_evaluation_input(
        method="post",
        path="/v1/orders/:id",
        headers={"X-Trace": "abc"},
        params={"id": "777"},
        body={"amount": 9000},
    )
    assert got.selector.keys == {"method": "POST", "path": "/v1/orders/:id"}
    assert got.facts.headers == {"x-trace": "abc"}, "headers lower-cased (Node/Java/Go parity)"
    assert got.facts.query == {} and got.facts.meta == {}, "absent buckets are objects, not None"


def test_now_is_left_for_encoding_to_fill():
    # Callers must not have to know about the engine's missing clock: a windowed
    # rule would evaluate as inactive if `now` were simply omitted downstream.
    assert klswitch.build_evaluation_input(method="GET", path="/x").now is None

    from klswitch._json import encode_evaluation_input

    encoded = encode_evaluation_input(klswitch.build_evaluation_input(method="GET", path="/x"))
    assert '"now":' in encoded.replace(" ", ""), "encoding fills the trusted clock"


def test_template_converters_target_the_engine_dialect():
    assert klswitch.flask_template_to_engine_path("/v1/orders/<id>") == "/v1/orders/:id"
    assert klswitch.fastapi_template_to_engine_path("/v1/orders/{id}") == "/v1/orders/:id"
