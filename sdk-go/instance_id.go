package klswitch

import (
	"fmt"
	"os"
	"regexp"
	"strings"
	"time"
)

// Instance-key resolution.
//
// The instance key answers "which runtime is this?". The token answers
// "which service is this?". They're complementary: one token is shared
// across every pod of a service; each pod has a distinct instance key.
//
// Resolution order (first valid wins):
//
//  1. config.instanceID               — operator passed it explicitly
//  2. KS_INSTANCE_ID env var          — env-only config
//  3. POD_NAME env var                — k8s downward API convention
//  4. {hostname}-{pid}-{startMs}      — deterministic per-process fallback
//
// Invalid values (charset / length) are skipped with a warning and the
// chain continues — never crash; the operator is giving a hint, not
// blocking their app boot.

// InstanceIDSource identifies which step in the resolution chain produced the value.
type InstanceIDSource int

const (
	InstanceIDSourceConfig InstanceIDSource = iota
	InstanceIDSourceEnv
	InstanceIDSourcePod
	InstanceIDSourceDerived
)

func (s InstanceIDSource) String() string {
	switch s {
	case InstanceIDSourceConfig:
		return "config"
	case InstanceIDSourceEnv:
		return "env"
	case InstanceIDSourcePod:
		return "pod"
	case InstanceIDSourceDerived:
		return "derived"
	default:
		return "unknown"
	}
}

// InstanceIDResolution carries the resolved value plus the source it came from.
type InstanceIDResolution struct {
	Value  string
	Source InstanceIDSource
}

// MaxInstanceIDLen is the soft cap on instance-id length. Tracks the cross-language contract.
const MaxInstanceIDLen = 128

var validInstanceID = regexp.MustCompile(`^[a-zA-Z0-9_\-.:/]+$`)

// IsValidInstanceID returns true iff v is non-empty and matches the soft-validation rules.
func IsValidInstanceID(v string) bool {
	trimmed := strings.TrimSpace(v)
	if trimmed == "" || len(trimmed) > MaxInstanceIDLen {
		return false
	}
	return validInstanceID.MatchString(trimmed)
}

// resolveInstanceID walks the fallback chain. envLookup is injectable for tests;
// in production it's os.Getenv.
func resolveInstanceID(configValue string, envLookup func(string) string, logger Logger) InstanceIDResolution {
	if envLookup == nil {
		envLookup = os.Getenv
	}
	candidates := []struct {
		value  string
		source InstanceIDSource
	}{
		{configValue, InstanceIDSourceConfig},
		{envLookup("KS_INSTANCE_ID"), InstanceIDSourceEnv},
		{envLookup("POD_NAME"), InstanceIDSourcePod},
	}

	for _, c := range candidates {
		if c.value == "" {
			continue
		}
		if IsValidInstanceID(c.value) {
			return InstanceIDResolution{
				Value:  strings.TrimSpace(c.value),
				Source: c.source,
			}
		}
		if logger != nil {
			logger.Warn("instanceID candidate rejected — falling through", map[string]any{
				"source": c.source.String(),
				"reason": "value violates charset/length rules",
			})
		}
	}
	return InstanceIDResolution{Value: deriveInstanceID(), Source: InstanceIDSourceDerived}
}

var nonInstanceIDChar = regexp.MustCompile(`[^a-zA-Z0-9_\-.:/]+`)

func deriveInstanceID() string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		host = "unknown"
	}
	// Hostnames can contain characters outside our validation charset
	// (rarely, but possible). Replace anything we don't allow with `-`.
	host = nonInstanceIDChar.ReplaceAllString(host, "-")
	if host == "" {
		host = "unknown"
	}
	return fmt.Sprintf("%s-%d-%d", host, os.Getpid(), time.Now().UnixMilli())
}
