package klswitch

import (
	"os"
	"strings"
)

// Header construction for every backend call (rules poll,
// decisions).
//
// Built centrally so the wire-level shape stays consistent across
// pollers / reporters and so SDK-controlled headers (auth, instance
// key, user agent) can't be accidentally overridden by the operator's
// extraHeaders.
//
// X-Env-Hint is observational — the backend derives the authoritative
// env from the token. The hint exists so a misconfigured deploy (prod
// token distributed to staging pods) is loud rather than silent.

var envHintVars = []string{
	"KS_ENV",
	"POD_NAMESPACE",
	"K_SERVICE_REVISION",
}

var reservedHeaders = []string{
	"authorization",
	"x-instance-key",
	"user-agent",
}

// HeaderInputs captures everything buildHeaders needs.
type headerInputs struct {
	token        string
	instanceKey  string
	sdkVersion   string
	extraHeaders map[string]string
	envHint      string // explicit override; empty means resolve from envLookup
	envLookup    func(string) string
}

// buildHeaders returns the header set for one outbound call. Operator-supplied
// extras are merged first; SDK-controlled headers always win so a typo in
// `extraHeaders` can't override Authorization or X-Instance-Key.
func buildHeaders(in headerInputs, logger Logger) map[string]string {
	envLookup := in.envLookup
	if envLookup == nil {
		envLookup = os.Getenv
	}

	headers := make(map[string]string, 6+len(in.extraHeaders))
	for k, v := range in.extraHeaders {
		headers[k] = v
	}
	warnIfReservedOverridden(in.extraHeaders, logger)

	headers["Authorization"] = "Bearer " + in.token
	headers["X-Instance-Key"] = in.instanceKey
	headers["User-Agent"] = "klswitch-go/" + in.sdkVersion
	headers["Accept-Encoding"] = "gzip"

	hint := in.envHint
	if hint == "" {
		hint = resolveEnvHint(envLookup)
	}
	if hint != "" {
		headers["X-Env-Hint"] = hint
	}
	return headers
}

// resolveEnvHint returns the first non-empty value from the standard
// env-hint variables, trimmed.
func resolveEnvHint(envLookup func(string) string) string {
	for _, v := range envHintVars {
		value := envLookup(v)
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			return trimmed
		}
	}
	return ""
}

func warnIfReservedOverridden(extras map[string]string, logger Logger) {
	if logger == nil {
		return
	}
	for key := range extras {
		lower := strings.ToLower(key)
		for _, r := range reservedHeaders {
			if lower == r {
				logger.Warn("extraHeaders includes a reserved header — SDK value wins", map[string]any{
					"header": key,
				})
				break
			}
		}
	}
}
