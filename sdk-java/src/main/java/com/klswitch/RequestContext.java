package com.klswitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Map;

/**
 * Framework-agnostic translation of request parts into the engine's evaluation
 * input. Adapters (and hand-written integrations) call this; no framework types
 * leak in. Normalizes the method to uppercase, uses the caller-supplied templated
 * path (the engine matches method/path exactly — rule-model §8), and ensures all
 * five fact buckets are present as objects.
 */
public final class RequestContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestContext() {}

    /**
     * Build the `EvaluationInput` JSON string for {@code Client.evaluate}.
     *
     * <p>Injects a trusted {@code now} (epoch millis) so rules carrying an {@code effective}
     * window evaluate correctly — the engine has no clock. Prefer this over hand-building the
     * input JSON; see {@code Client.evaluate} for the hand-built case.
     */
    /**
     * Accepts any of the three common route-template dialects and returns the engine's.
     *
     * <p>{@code {id}} (Spring, FastAPI/Starlette), {@code <id>} / {@code <int:id>}
     * (Flask/Werkzeug) and {@code :id} (engine, Express) all become {@code :id}.
     *
     * <p>Applied automatically by {@link #toEvaluationInput}. A host integrating without an
     * adapter otherwise has to know the engine's exact dialect, and the failure when they get it
     * wrong is close to invisible: rules whose selectors carry no path parameter still match, so
     * the integration looks healthy while every parameterised rule silently misses. Braces and
     * angle brackets are not valid in an engine selector, so rewriting them cannot change the
     * meaning of a path that was already correct.
     */
    public static String canonicalizeTemplate(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        // <int:id> / <id>  ->  :id   (drop any converter prefix)
        String out = ANGLE_PARAM.matcher(path).replaceAll(":$1");
        // {file_path:path} / {id}  ->  :file_path / :id   (drop any converter suffix)
        return BRACE_PARAM.matcher(out).replaceAll(":$1");
    }

    private static final java.util.regex.Pattern ANGLE_PARAM =
            java.util.regex.Pattern.compile("<(?:[^:>]+:)?([^>]+)>");
    private static final java.util.regex.Pattern BRACE_PARAM =
            java.util.regex.Pattern.compile("\\{([^}:]+)(?::[^}]+)?\\}");

    public static String toEvaluationInput(
            String method,
            String path,
            Map<String, Object> headers,
            Map<String, Object> query,
            Map<String, Object> params,
            Object body) {
        ObjectNode input = MAPPER.createObjectNode();
        ObjectNode selector = input.putObject("selector");
        selector.put("kind", "api");
        ObjectNode keys = selector.putObject("keys");
        keys.put("method", method == null ? "" : method.toUpperCase(Locale.ROOT));
        keys.put("path", path == null ? "" : canonicalizeTemplate(path));

        ObjectNode facts = input.putObject("facts");
        facts.set("headers", MAPPER.valueToTree(headers == null ? Map.of() : headers));
        facts.set("query", MAPPER.valueToTree(query == null ? Map.of() : query));
        facts.set("params", MAPPER.valueToTree(params == null ? Map.of() : params));
        facts.set("body", objectOrEmpty(body));
        facts.putObject("meta");

        // Inject the trusted clock the engine lacks (for effective-window rules);
        // epoch millis, consistent with the other wrappers.
        input.put("now", System.currentTimeMillis());
        return input.toString();
    }

    /** A body that isn't a JSON object (null, array, primitive) becomes {} — facts.body must be an object. */
    private static JsonNode objectOrEmpty(Object body) {
        JsonNode node = MAPPER.valueToTree(body);
        return node != null && node.isObject() ? node : MAPPER.createObjectNode();
    }
}
