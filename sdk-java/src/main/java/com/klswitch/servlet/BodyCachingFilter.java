package com.klswitch.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

/**
 * Caches the request body so a later component can read it without consuming the stream.
 *
 * <p>Exists for the Spring path. Neither half of that integration is complete on its own: the
 * servlet {@link KLSwitchFilter} has the body but runs before Spring resolves a route, so it
 * cannot see the {@code /api/items/{id}} template; the Spring interceptor has the template but
 * runs after the stream is positioned, so it cannot read the body. The result was that the Spring
 * starter evaluated every request with <em>no body facts at all</em>.
 *
 * <p>That is worse than it sounds. A rule like {@code body.amount gt 10000} silently never
 * matched — but a negated one like {@code not(body.user.id exists)} silently matched
 * <em>everything</em>, because an absent field makes {@code exists} false and {@code not} inverts
 * it. The Spring adapter therefore blocked traffic that should have passed, and the 403 looked
 * exactly like the rule working.
 *
 * <p>This filter only buffers and republishes; it never evaluates or blocks. Bodies larger than
 * the cap stream through unbuffered and simply yield no body facts, which is the same degradation
 * {@link KLSwitchFilter} already applies.
 */
public final class BodyCachingFilter implements Filter {

    /** Request attribute holding the parsed body, read by the Spring interceptor. */
    public static final String BODY_ATTR = "com.klswitch.bodyFacts";

    private final int maxBodyBytes;

    public BodyCachingFilter() {
        this(1 << 20);
    }

    public BodyCachingFilter(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest httpReq)) {
            chain.doFilter(req, res);
            return;
        }
        KLSwitchFilter.BodyCacheRequest wrapper =
                new KLSwitchFilter.BodyCacheRequest(httpReq, maxBodyBytes);
        Map<String, Object> body =
                KLSwitchFilter.parseJsonBodyForReuse(httpReq.getContentType(), wrapper.bufferedBody());
        if (body != null) {
            wrapper.setAttribute(BODY_ATTR, body);
        }
        chain.doFilter(wrapper, res);
    }
}
