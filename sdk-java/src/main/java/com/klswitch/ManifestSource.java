package com.klswitch;

import java.util.List;
import java.util.function.Supplier;

/**
 * What an operator passes to the SDK so the manifest reporter knows
 * "what routes does this app have registered?" Each framework adapter
 * ships its own helper that returns one of these (e.g.
 * {@code JaxRsManifestSource.of(...)}, {@code ServletManifestSource.of(ctx)}).
 *
 * <p>Reusable across reports — {@link #collect()} is called once at init
 * and again on every {@code client.reportManifest()}.
 */
public final class ManifestSource {

    private final String framework;
    private final Supplier<List<ManifestRoute>> collect;

    private ManifestSource(String framework, Supplier<List<ManifestRoute>> collect) {
        if (framework == null || framework.isBlank()) {
            throw new IllegalArgumentException("ManifestSource: framework is required");
        }
        if (collect == null) {
            throw new IllegalArgumentException("ManifestSource: collect supplier is required");
        }
        this.framework = framework;
        this.collect = collect;
    }

    public static ManifestSource of(String framework, Supplier<List<ManifestRoute>> collect) {
        return new ManifestSource(framework, collect);
    }

    public String framework() {
        return framework;
    }

    public List<ManifestRoute> collect() {
        List<ManifestRoute> out = collect.get();
        return out != null ? out : List.of();
    }
}
