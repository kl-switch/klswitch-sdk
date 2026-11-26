package com.klswitch.jaxrs;

import com.klswitch.ManifestRoute;
import com.klswitch.ManifestSource;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JAX-RS route discovery.
 *
 * <p>JAX-RS has no built-in "tell me every registered resource" API at the
 * spec level — operators pass either a {@link jakarta.ws.rs.core.Application}
 * or an explicit set of resource classes (matches what they already pass to
 * the JAX-RS runtime).
 *
 * <p>For each resource class we walk every public method, detect the HTTP
 * verb via JAX-RS designator annotations ({@code @GET}/{@code @POST}/...) ,
 * combine the class-level and method-level {@code @Path}, then prefer the
 * {@link KLSwitchBinding#path()} override if present so the discovered
 * route matches whatever the operator declared for evaluation. Otherwise
 * the JAX-RS {@code {id}} / {@code {id:\\d+}} syntax is canonicalised to
 * the {@code :id} form rule selectors use across wrappers.
 */
public final class JaxRsManifestSource {

    private static final String FRAMEWORK = "jax-rs";

    /** "{name}" or "{name:regex}" — we strip the regex spec. */
    private static final Pattern PARAM = Pattern.compile("\\{([^:{}]+)(?::[^{}]+)?\\}");

    private JaxRsManifestSource() {}

    /**
     * Walks {@code application.getClasses()} + {@code application.getSingletons()}
     * for resource classes. Same shape the operator already configures the
     * JAX-RS runtime with, so no extra registration is needed.
     */
    public static ManifestSource of(Application application) {
        return ManifestSource.of(FRAMEWORK, () -> discoverFromApplication(application));
    }

    /** Explicit set of resource classes — useful when not using {@link Application}. */
    public static ManifestSource ofClasses(Collection<Class<?>> resourceClasses) {
        return ManifestSource.of(FRAMEWORK, () -> discover(resourceClasses));
    }

    /** Exposed for tests. */
    @SuppressWarnings("deprecation") // Application.getSingletons() is still in widespread use.
    public static List<ManifestRoute> discoverFromApplication(Application application) {
        if (application == null) return List.of();
        Set<Class<?>> classes = new LinkedHashSet<>();
        if (application.getClasses() != null) {
            classes.addAll(application.getClasses());
        }
        if (application.getSingletons() != null) {
            for (Object s : application.getSingletons()) {
                if (s != null) classes.add(s.getClass());
            }
        }
        return discover(classes);
    }

    /** Exposed for tests. */
    public static List<ManifestRoute> discover(Collection<Class<?>> resourceClasses) {
        if (resourceClasses == null || resourceClasses.isEmpty()) return List.of();
        List<ManifestRoute> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();    // de-dup (method, path) within one app
        for (Class<?> cls : resourceClasses) {
            if (cls == null) continue;
            collectFromClass(cls, out, seen);
        }
        return out;
    }

    private static void collectFromClass(Class<?> cls, List<ManifestRoute> out, Set<String> seen) {
        Path classPath = cls.getAnnotation(Path.class);
        KLSwitchBinding classBinding = cls.getAnnotation(KLSwitchBinding.class);
        String basePath = classPath != null ? normaliseSegment(classPath.value()) : "";

        for (Method method : cls.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            String httpMethod = httpVerb(method);
            if (httpMethod == null) continue;

            String methodSeg = "";
            Path mPath = method.getAnnotation(Path.class);
            if (mPath != null) methodSeg = normaliseSegment(mPath.value());

            KLSwitchBinding mBinding = method.getAnnotation(KLSwitchBinding.class);
            // Operator's @KLSwitchBinding(path=...) wins — that's the path
            // they've authored rules against. Otherwise, derive from @Path.
            String declared = null;
            if (mBinding != null && !mBinding.path().isEmpty()) {
                declared = mBinding.path();
            } else if (classBinding != null && !classBinding.path().isEmpty() && methodSeg.isEmpty()) {
                declared = classBinding.path();
            }

            String path = declared != null
                    ? declared
                    : convertToColonStyle(joinPaths(basePath, methodSeg));

            String key = httpMethod + " " + path;
            if (!seen.add(key)) continue;

            out.add(new ManifestRoute(
                    httpMethod, path, cls.getSimpleName() + "." + method.getName()));
        }
    }

    /** Resolve the HTTP verb either from JAX-RS designators or a custom {@code @HttpMethod}. */
    private static String httpVerb(Method method) {
        if (method.isAnnotationPresent(GET.class))     return "GET";
        if (method.isAnnotationPresent(POST.class))    return "POST";
        if (method.isAnnotationPresent(PUT.class))     return "PUT";
        if (method.isAnnotationPresent(DELETE.class))  return "DELETE";
        if (method.isAnnotationPresent(PATCH.class))   return "PATCH";
        if (method.isAnnotationPresent(HEAD.class))    return "HEAD";
        if (method.isAnnotationPresent(OPTIONS.class)) return "OPTIONS";
        // Custom verbs: any annotation that is itself meta-annotated with
        // @HttpMethod (e.g. a third-party @PROPFIND).
        for (Annotation a : method.getAnnotations()) {
            HttpMethod meta = a.annotationType().getAnnotation(HttpMethod.class);
            if (meta != null) return meta.value().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /** Strip leading/trailing whitespace; ensure a leading slash and no trailing slash. */
    static String normaliseSegment(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (!t.startsWith("/")) t = "/" + t;
        if (t.length() > 1 && t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    static String joinPaths(String base, String sub) {
        if (sub.isEmpty()) return base.isEmpty() ? "/" : base;
        if (base.isEmpty() || base.equals("/")) return sub;
        return base + sub;
    }

    /** {@code "{id}"} or {@code "{id:\\d+}"} → {@code ":id"}; everything else is left alone. */
    public static String convertToColonStyle(String path) {
        if (path == null || path.indexOf('{') < 0) return path;
        Matcher m = PARAM.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(":" + m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
