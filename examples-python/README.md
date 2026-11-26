# klswitch-examples-python

Minimal Flask **and** FastAPI demo servers exercising `sdk-python` over real
HTTP — the Python counterparts to the Node `examples/`, Java `examples-java/`,
and Go `examples-go/` demos.

Both servers wire the SDK's packaged adapter (`KLSwitchExtension` for Flask,
`klswitch_depends` for FastAPI) and reuse the **same three demo rules** as
the Node / Java / Go example servers, so the curl invocations below produce
identical responses across all five languages.

## Setup

The example installs the local SDK directly so changes in `sdk-python/` are
picked up without a release cycle.

```bash
cd examples-python
python -m venv .venv
source .venv/bin/activate
pip install -e ../sdk-python[flask,fastapi]
pip install uvicorn pytest pytest-asyncio
```

## Integration tests (the main validation)

```bash
pytest -q
```

Tests run each app via its in-process transport — Flask through
`app.test_client()`, FastAPI through `httpx.ASGITransport` — so they exercise
real WSGI / ASGI dispatch without binding a socket. All twelve tests pass on
the bundled WASM engine:

```
............                                                             [100%]
12 passed in 0.70s
```

## Run them as real servers

```bash
# Flask (Werkzeug dev server)
python flask_server.py            # listens on :8087
PORT=9000 python flask_server.py  # custom port

# FastAPI (uvicorn)
python fastapi_server.py          # listens on :8087
PORT=9000 python fastapi_server.py
```

```bash
B=localhost:8087
J='content-type: application/json'

# Small payment: no rule matches → 200 OK, matched: []
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":100}'

# Large payment: block_large_payment (gt 50000) → 429
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":70000}'

# Restricted region: block_restricted_region (in ["KP","IR"]) → 451
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":10,"country":"KP"}'

# Admin via query param: flag_admin_access (non-blocking) → 200 with matched flag
curl -s "$B/v1/users/42?role=admin"

# Admin via header: same rule fires through the `any` branch
curl -s   $B/v1/users/42 -H 'X-Impersonate: ops-user'

# Engine status
curl -s   $B/health
```

Run that same block against either server, or against any of the Node / Java /
Go example servers, and the outputs line up: same status codes, same
`ruleId`, same `matched` shape.

## What this demo proves

- The Python SDK works end-to-end against real HTTP semantics — not just the
  shared compliance fixtures.
- Wiring a SDK `Client` (sync) or `AsyncClient` (async) into the dominant
  Python web frameworks is a few lines of code.
- Behaviour is **identical** to the Node / Java / Go demos for the same three
  rules — same status codes, same `ruleId`, same `matched` shape.
- Tests run in-process (no port binding, no flakiness).

## Layout

| File | Purpose |
|------|---------|
| `demo_rules.py` | Three demo rules (mirrors `examples-java/.../demo-rules.json` and `examples-go/rules.go`) |
| `flask_server.py` | Flask app: `build_app(client)` + `main()` for direct run |
| `fastapi_server.py` | FastAPI app: `build_app(client)` + module-level `app` for uvicorn + `main()` |
| `test_servers.py` | End-to-end checks for every demo rule, both servers |
| `pyproject.toml` | Test config (`asyncio_mode = "auto"`) + declared deps |

## FastAPI block-response shape

By default, FastAPI's `klswitch_depends` raises `HTTPException`, which
wraps the JSON body in `{"detail": ...}`. To make the FastAPI demo's
**block** responses match the Node/Java/Go/Flask demos byte-for-byte (i.e. a
top-level `{"blocked": true, "ruleId": ..., "action": ...}`), the example
registers a custom exception class + handler in `fastapi_server.py`:

```python
class KLSwitchBlock(Exception):
    def __init__(self, status_code, body):
        self.status_code, self.body = status_code, body

async def _block_handler(result, request):
    for m in result.matched:
        if m.action.type == "block":
            status = (m.action.payload or {}).get("statusCode", 403) or 403
            raise KLSwitchBlock(
                status_code=status,
                body={"blocked": True, "ruleId": m.id, "action": {...}},
            )
    return result

@app.exception_handler(KLSwitchBlock)
async def _on_block(request, exc):
    return JSONResponse(content=exc.body, status_code=exc.status_code)

ks_dep = klswitch_depends(client, result_handler=_block_handler)
```

You can skip this entirely and live with the default `{"detail": {...}}`
wrapping — that's the more idiomatic FastAPI choice. The demo shows the
non-default to keep the curl block consistent across all five language
example servers.

## Production deployment notes

The bundled servers use Werkzeug's dev server (Flask) and uvicorn (FastAPI)
for the demo. For production, put a real WSGI / ASGI server in front:

- **Flask:** gunicorn / waitress / uwsgi
- **FastAPI:** uvicorn behind nginx, or hypercorn

The SDK caps body reads at 1 MiB by inspecting `Content-Length`, but
chunked uploads (no Content-Length) bypass that check. Bound them at the
framework layer:

- Flask sets `app.config["MAX_CONTENT_LENGTH"] = 1 << 20` (1 MiB) in
  `flask_server.py:build_app` — Werkzeug rejects oversized bodies before
  the request handler runs.
- FastAPI passes `h11_max_incomplete_event_size=1 << 20` to uvicorn in
  `fastapi_server.py:main` — same effect at the ASGI server layer.
