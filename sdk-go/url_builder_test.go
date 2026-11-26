package klswitch

import "testing"

func TestURL_DefaultsWhenNothingSupplied(t *testing.T) {
	u := resolveURLs("", "", "", "")
	if u.Rules != DefaultBaseURL+"/v1/ruleset" {
		t.Errorf("rules: got %q", u.Rules)
	}
	if u.Decisions != DefaultBaseURL+"/v1/analytics" {
		t.Errorf("decisions: got %q", u.Decisions)
	}
	if u.Manifest != DefaultBaseURL+"/v1/manifest" {
		t.Errorf("manifest: got %q", u.Manifest)
	}
}

func TestURL_ManifestOverrideTakesPrecedence(t *testing.T) {
	u := resolveURLs("http://api", "", "", "http://custom/manifest")
	if u.Manifest != "http://custom/manifest" {
		t.Errorf("manifest override didn't win: %q", u.Manifest)
	}
	if u.Rules != "http://api/v1/ruleset" {
		t.Errorf("rules unexpected: %q", u.Rules)
	}
}

func TestURL_BaseURLFlowsToEveryEndpoint(t *testing.T) {
	u := resolveURLs("http://self-hosted:8080", "", "", "")
	if u.Rules != "http://self-hosted:8080/v1/ruleset" {
		t.Errorf("rules: got %q", u.Rules)
	}
	if u.Decisions != "http://self-hosted:8080/v1/analytics" {
		t.Errorf("decisions: got %q", u.Decisions)
	}
}

func TestURL_TrailingSlashOnBaseURLNormalized(t *testing.T) {
	u := resolveURLs("http://api/", "", "", "")
	if u.Rules != "http://api/v1/ruleset" {
		t.Errorf("rules: got %q", u.Rules)
	}
}

func TestURL_PerEndpointOverridesWin(t *testing.T) {
	u := resolveURLs("http://ignored", "http://custom/rules", "http://custom/dec", "")
	if u.Rules != "http://custom/rules" || u.Decisions != "http://custom/dec" {
		t.Errorf("unexpected: %+v", u)
	}
}

func TestURL_MixBaseAndOneOverride(t *testing.T) {
	u := resolveURLs("http://api.example.com", "http://separate-cdn/rules", "", "")
	if u.Rules != "http://separate-cdn/rules" {
		t.Errorf("rules override didn't win: %q", u.Rules)
	}
}
