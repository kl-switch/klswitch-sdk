"""Demo ruleset shared by ``flask_server.py`` and ``fastapi_server.py``.

Byte-identical to ``examples-java/src/main/resources/demo-rules.json`` and to
``examples-go/rules.go`` so the README's curl block produces the same status
codes + matched rule ids against any of the five example servers
(Node Express / Node Fastify / Java / Go / Python).
"""

from __future__ import annotations

from klswitch import Action, Rule, RuleSetPayload, Selector


DEMO_RULES = RuleSetPayload(
    schema_version="1.0",
    rules_version=1,
    rules=[
        Rule(
            id="block_large_payment",
            name="Block large payment",
            selector=Selector(
                kind="api", keys={"method": "POST", "path": "/v1/payments/:id"}
            ),
            enabled=True,
            priority=100,
            condition={
                "all": [{"field": "body.amount", "operator": "gt", "value": 50000}]
            },
            action=Action(
                type="block",
                payload={"statusCode": 429, "code": "PAYMENT_TOO_LARGE"},
            ),
        ),
        Rule(
            id="block_restricted_region",
            name="Block restricted region",
            selector=Selector(
                kind="api", keys={"method": "POST", "path": "/v1/payments/:id"}
            ),
            enabled=True,
            priority=110,
            condition={
                "all": [
                    {"field": "body.country", "operator": "in", "value": ["KP", "IR"]}
                ]
            },
            action=Action(
                type="block",
                payload={"statusCode": 451, "code": "RESTRICTED_REGION"},
            ),
        ),
        Rule(
            id="flag_admin_access",
            name="Flag admin access",
            selector=Selector(
                kind="api", keys={"method": "GET", "path": "/v1/users/:id"}
            ),
            enabled=True,
            priority=50,
            condition={
                "any": [
                    {"field": "query.role", "operator": "eq", "value": "admin"},
                    {"field": "headers.x-impersonate", "operator": "exists"},
                ]
            },
            action=Action(type="flag", payload={"code": "ADMIN_ACCESS"}),
        ),
    ],
)
