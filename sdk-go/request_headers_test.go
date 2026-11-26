package klswitch

import "testing"

func TestHeaders_CoreAlwaysSet(t *testing.T) {
	h := buildHeaders(headerInputs{
		token:       "ks_live_test",
		instanceKey: "pod-7f4a",
		sdkVersion:  "0.1.0",
		envLookup:   stubEnv(map[string]string{}),
	}, nil)
	if h["Authorization"] != "Bearer ks_live_test" {
		t.Errorf("Authorization: %q", h["Authorization"])
	}
	if h["X-Instance-Key"] != "pod-7f4a" {
		t.Errorf("X-Instance-Key: %q", h["X-Instance-Key"])
	}
	if h["User-Agent"] != "klswitch-go/0.1.0" {
		t.Errorf("User-Agent: %q", h["User-Agent"])
	}
	if h["Accept-Encoding"] != "gzip" {
		t.Errorf("Accept-Encoding: %q", h["Accept-Encoding"])
	}
}

func TestHeaders_ExtraMergedButReservedNotOverridable(t *testing.T) {
	h := buildHeaders(headerInputs{
		token:       "ks_live_test",
		instanceKey: "i",
		sdkVersion:  "0.1.0",
		extraHeaders: map[string]string{
			"X-Custom":        "value",
			"Authorization":   "Bearer ATTACK",
			"x-instance-key":  "ATTACK",
		},
		envLookup: stubEnv(map[string]string{}),
	}, nil)
	if h["X-Custom"] != "value" {
		t.Errorf("X-Custom not merged: %q", h["X-Custom"])
	}
	if h["Authorization"] != "Bearer ks_live_test" {
		t.Errorf("Authorization overridden: %q", h["Authorization"])
	}
	if h["X-Instance-Key"] != "i" {
		t.Errorf("X-Instance-Key overridden: %q", h["X-Instance-Key"])
	}
}

func TestHeaders_EnvHintKsEnv(t *testing.T) {
	h := buildHeaders(headerInputs{
		token:       "t",
		instanceKey: "i",
		sdkVersion:  "0",
		envLookup:   stubEnv(map[string]string{"KS_ENV": "staging"}),
	}, nil)
	if h["X-Env-Hint"] != "staging" {
		t.Errorf("X-Env-Hint: %q", h["X-Env-Hint"])
	}
}

func TestHeaders_EnvHintPodNamespace(t *testing.T) {
	h := buildHeaders(headerInputs{
		token:       "t",
		instanceKey: "i",
		sdkVersion:  "0",
		envLookup:   stubEnv(map[string]string{"POD_NAMESPACE": "prod"}),
	}, nil)
	if h["X-Env-Hint"] != "prod" {
		t.Errorf("X-Env-Hint: %q", h["X-Env-Hint"])
	}
}

func TestHeaders_EnvHintOmittedWhenUnresolvable(t *testing.T) {
	h := buildHeaders(headerInputs{
		token:       "t",
		instanceKey: "i",
		sdkVersion:  "0",
		envLookup:   stubEnv(map[string]string{}),
	}, nil)
	if _, ok := h["X-Env-Hint"]; ok {
		t.Errorf("X-Env-Hint should be omitted; got %q", h["X-Env-Hint"])
	}
}

func TestHeaders_ExplicitEnvHintWinsOverEnv(t *testing.T) {
	h := buildHeaders(headerInputs{
		token:       "t",
		instanceKey: "i",
		sdkVersion:  "0",
		envHint:     "explicit",
		envLookup:   stubEnv(map[string]string{"KS_ENV": "from-env"}),
	}, nil)
	if h["X-Env-Hint"] != "explicit" {
		t.Errorf("X-Env-Hint: %q", h["X-Env-Hint"])
	}
}

func TestHeaders_ResolveEnvHintChainOrder(t *testing.T) {
	if v := resolveEnvHint(stubEnv(map[string]string{
		"KS_ENV": "a", "POD_NAMESPACE": "b", "K_SERVICE_REVISION": "c"})); v != "a" {
		t.Errorf("KS_ENV should win: %q", v)
	}
	if v := resolveEnvHint(stubEnv(map[string]string{
		"POD_NAMESPACE": "b", "K_SERVICE_REVISION": "c"})); v != "b" {
		t.Errorf("POD_NAMESPACE should win: %q", v)
	}
	if v := resolveEnvHint(stubEnv(map[string]string{"K_SERVICE_REVISION": "c"})); v != "c" {
		t.Errorf("K_SERVICE_REVISION should win: %q", v)
	}
	if v := resolveEnvHint(stubEnv(map[string]string{})); v != "" {
		t.Errorf("expected empty: %q", v)
	}
}

func TestHeaders_ResolveEnvHintSkipsEmpty(t *testing.T) {
	if v := resolveEnvHint(stubEnv(map[string]string{"KS_ENV": "", "POD_NAMESPACE": "b"})); v != "b" {
		t.Errorf("empty should be skipped: %q", v)
	}
	if v := resolveEnvHint(stubEnv(map[string]string{"KS_ENV": "   ", "POD_NAMESPACE": "b"})); v != "b" {
		t.Errorf("whitespace should be skipped: %q", v)
	}
}
