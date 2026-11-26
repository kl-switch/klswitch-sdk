package klswitch

import (
	"regexp"
	"testing"
)

func TestInstanceID_IsValidAcceptsAllowedCharset(t *testing.T) {
	for _, v := range []string{
		"pod-7f4a",
		"payments-api-prod-0",
		"arn:aws:ecs:us-east-1:123/cluster/task",
		"a",
		"A_B.C-D:E/F",
	} {
		if !IsValidInstanceID(v) {
			t.Errorf("expected valid: %q", v)
		}
	}
}

func TestInstanceID_IsValidRejectsBad(t *testing.T) {
	long := make([]byte, MaxInstanceIDLen+1)
	for i := range long {
		long[i] = 'a'
	}
	for _, v := range []string{
		"",
		"   ",
		"has spaces",
		"with#special",
		string(long),
	} {
		if IsValidInstanceID(v) {
			t.Errorf("expected invalid: %q", v)
		}
	}
}

func stubEnv(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestInstanceID_ResolveExplicitConfigWins(t *testing.T) {
	r := resolveInstanceID("from-config",
		stubEnv(map[string]string{"KS_INSTANCE_ID": "from-env", "POD_NAME": "from-pod"}), nil)
	if r.Value != "from-config" || r.Source != InstanceIDSourceConfig {
		t.Errorf("unexpected: %+v", r)
	}
}

func TestInstanceID_ResolveKsEnvBeatsPodName(t *testing.T) {
	r := resolveInstanceID("",
		stubEnv(map[string]string{"KS_INSTANCE_ID": "from-env", "POD_NAME": "from-pod"}), nil)
	if r.Value != "from-env" || r.Source != InstanceIDSourceEnv {
		t.Errorf("unexpected: %+v", r)
	}
}

func TestInstanceID_ResolvePodWhenOnlyItIsSet(t *testing.T) {
	r := resolveInstanceID("", stubEnv(map[string]string{"POD_NAME": "from-pod"}), nil)
	if r.Value != "from-pod" || r.Source != InstanceIDSourcePod {
		t.Errorf("unexpected: %+v", r)
	}
}

func TestInstanceID_DerivedFallbackPattern(t *testing.T) {
	r := resolveInstanceID("", stubEnv(map[string]string{}), nil)
	if r.Source != InstanceIDSourceDerived {
		t.Errorf("expected derived source, got %s", r.Source)
	}
	if !regexp.MustCompile(`^[\w\-.:]+-\d+-\d+$`).MatchString(r.Value) {
		t.Errorf("derived value doesn't match expected pattern: %q", r.Value)
	}
}

func TestInstanceID_InvalidExplicitFallsThrough(t *testing.T) {
	r := resolveInstanceID("has spaces", stubEnv(map[string]string{"KS_INSTANCE_ID": "from-env"}), nil)
	if r.Value != "from-env" || r.Source != InstanceIDSourceEnv {
		t.Errorf("unexpected: %+v", r)
	}
}

func TestInstanceID_AllInvalidFallsToDerived(t *testing.T) {
	r := resolveInstanceID("bad!",
		stubEnv(map[string]string{"KS_INSTANCE_ID": "bad@", "POD_NAME": "bad#"}), nil)
	if r.Source != InstanceIDSourceDerived {
		t.Errorf("expected derived; got %s with value %q", r.Source, r.Value)
	}
}

func TestInstanceID_TrimsWhitespace(t *testing.T) {
	r := resolveInstanceID("  pod-7f4a  ", stubEnv(map[string]string{}), nil)
	if r.Value != "pod-7f4a" || r.Source != InstanceIDSourceConfig {
		t.Errorf("unexpected: %+v", r)
	}
}
