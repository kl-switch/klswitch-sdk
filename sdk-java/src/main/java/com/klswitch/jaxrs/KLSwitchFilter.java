package com.klswitch.jaxrs;

import com.klswitch.Client;

/**
 * JAX-RS request filter that evaluates {@link KLSwitchBinding}-annotated
 * resources against the rule engine. Because this class carries
 * {@code @KLSwitchBinding} itself, JAX-RS name binding applies it only to
 * resources that carry the same annotation.
 *
 * <p><strong>A resource you forget to annotate is silently unguarded.</strong>
 * It looks configured and is not. Prefer {@link KLSwitchGlobalFilter} unless
 * you specifically want some resources evaluated and others not:
 *
 * <pre>{@code
 * environment.jersey().register(new KLSwitchGlobalFilter(ks));   // every resource
 * }</pre>
 *
 * <p>Register one instance bound to your client, e.g.
 * {@code environment.jersey().register(new KLSwitchFilter(ks))}, then annotate
 * the resource classes or methods it should guard.
 *
 * <p>The result is stored under {@link AbstractKLSwitchFilter#RESULT_PROPERTY} for the resource to read
 * (e.g. for {@code flag}/{@code notify} actions). The request body (JSON) is read
 * and the stream reset so the resource can still consume it.
 *
 * <p>Only one guard may be mounted per {@link Client}; constructing a second one
 * throws {@link IllegalStateException}. See {@link Client#registerGuardMount}.
 */
@KLSwitchBinding
public final class KLSwitchFilter extends AbstractKLSwitchFilter {

    public KLSwitchFilter(Client ks) {
        super(ks, "the JAX-RS filter (@KLSwitchBinding-annotated resources)");
    }
}
