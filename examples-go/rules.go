package main

import ks "github.com/kl-switch/klswitch-sdk/sdk-go"

// p returns a pointer to n — used for the optional *int Priority field.
func p(n int) *int { return &n }

// DemoRules mirrors examples-java/src/main/resources/demo-rules.json so the
// curl examples in the README work identically across the Node, Java, and Go
// example servers.
var DemoRules = ks.RuleSetPayload{
	SchemaVersion: "1.0",
	RulesVersion:  1,
	Rules: []ks.Rule{
		{
			ID:   "block_large_payment",
			Name: "Block large payment",
			Selector: ks.Selector{
				Kind: "api",
				Keys: map[string]string{"method": "POST", "path": "/v1/payments/:id"},
			},
			Enabled:  true,
			Priority: p(100),
			Condition: ks.ConditionNode{
				All: []ks.ConditionNode{
					{Field: "body.amount", Operator: ks.OpGt, Value: 50000},
				},
			},
			Action: ks.Action{
				Type: "block",
				Payload: map[string]any{
					"statusCode": 429,
					"code":       "PAYMENT_TOO_LARGE",
				},
			},
		},
		{
			ID:   "block_restricted_region",
			Name: "Block restricted region",
			Selector: ks.Selector{
				Kind: "api",
				Keys: map[string]string{"method": "POST", "path": "/v1/payments/:id"},
			},
			Enabled:  true,
			Priority: p(110),
			Condition: ks.ConditionNode{
				All: []ks.ConditionNode{
					{Field: "body.country", Operator: ks.OpIn, Value: []string{"KP", "IR"}},
				},
			},
			Action: ks.Action{
				Type: "block",
				Payload: map[string]any{
					"statusCode": 451,
					"code":       "RESTRICTED_REGION",
				},
			},
		},
		{
			ID:   "flag_admin_access",
			Name: "Flag admin access",
			Selector: ks.Selector{
				Kind: "api",
				Keys: map[string]string{"method": "GET", "path": "/v1/users/:id"},
			},
			Enabled:  true,
			Priority: p(50),
			Condition: ks.ConditionNode{
				Any: []ks.ConditionNode{
					{Field: "query.role", Operator: ks.OpEq, Value: "admin"},
					{Field: "headers.x-impersonate", Operator: ks.OpExists},
				},
			},
			Action: ks.Action{
				Type:    "flag",
				Payload: map[string]any{"code": "ADMIN_ACCESS"},
			},
		},
	},
}
