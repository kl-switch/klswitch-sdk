package com.klswitch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One route discovered from a framework's registry.
 *
 * <p>Emitted by a {@link ManifestSource} and serialised inside the
 * {@code /v1/manifest} payload — the contract is frozen across wrappers
 * (see {@code docs/schema-drift-detection.md}).
 *
 * <p>{@code handler} and {@code fields} are best-effort; null when the
 * framework can't supply them. The console treats absence as "unknown",
 * not "empty".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestRoute(
        @JsonProperty("method") String method,
        @JsonProperty("path")   String path,
        @JsonProperty("handler") String handler,
        @JsonProperty("fields")  Fields fields) {

    public ManifestRoute(String method, String path) {
        this(method, path, null, null);
    }

    public ManifestRoute(String method, String path, String handler) {
        this(method, path, handler, null);
    }

    /** Best-effort field shape grouped by request location. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Fields(
            @JsonProperty("body")    List<Field> body,
            @JsonProperty("headers") List<Field> headers,
            @JsonProperty("params")  List<Field> params,
            @JsonProperty("query")   List<Field> query) {

        public Fields {
            body    = body    != null ? List.copyOf(body)    : List.of();
            headers = headers != null ? List.copyOf(headers) : List.of();
            params  = params  != null ? List.copyOf(params)  : List.of();
            query   = query   != null ? List.copyOf(query)   : List.of();
        }
    }

    /** One discovered field on a route. */
    public record Field(
            @JsonProperty("name") String name,
            @JsonProperty("type") String type) {}
}
