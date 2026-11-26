# klswitch-examples-java

Two runnable servers exercising `klswitch-sdk-java` — the Java counterpart to
the Node `examples/`. They are separate Maven modules because Dropwizard and
Spring Boot each bring their own embedded container and Jackson stack, and a
single classpath would resolve that conflict by luck.

| Module | Framework | What it demonstrates |
|---|---|---|
| [`dropwizard/`](dropwizard) | Dropwizard 4 (JAX-RS) | Guarding JAX-RS resources; templated paths from `@Path` |
| [`spring/`](spring) | Spring Boot 3 (Spring MVC) | One-filter mount; templated paths + `params.*` via `SpringRouteResolver` |

Both load the **same** `demo-rules.json` — the Spring module reads it straight
out of the Dropwizard module's resources — so a rule that blocks on one blocks
on the other.

```bash
mvn test        # both modules
mvn -pl spring test    # just one
```

---

## `dropwizard/` — Dropwizard 4

KLSwitch is wired into JAX-RS **resources** over **dummy services**, and the
integration is tested at the **resource layer** with Dropwizard's in-memory
`ResourceExtension` (no Jetty boot).

## Setup

The example depends on the **local** SDK, so install it to your `~/.m2` first:

```bash
( cd ../sdk-java && mvn install )   # builds + installs klswitch-sdk-java (bundles the .wasm)
cd ../examples-java
```

Both modules depend on that local install.

## Resource-layer tests (the main validation)

```bash
mvn test
```

`PaymentsResourceTest` / `UsersResourceTest` run each resource in an in-memory
Jersey container via `ResourceExtension` and assert the KLSwitch decisions
(block 429/451, flag, allow) through real HTTP semantics — fast, no server boot.

## Run it as a real server

```bash
mvn -q -DskipTests package
java -jar dropwizard/target/klswitch-examples-dropwizard-0.0.0.jar server dropwizard/config.yml   # port 8085 (admin 8086)
```

```bash
B=localhost:8085
curl -sX POST $B/v1/payments/abc -H 'content-type: application/json' -d '{"amount":100}'    # 200, matched []
curl -sX POST $B/v1/payments/abc -H 'content-type: application/json' -d '{"amount":70000}'   # 429 block_large_payment
curl -sX POST $B/v1/payments/abc -H 'content-type: application/json' -d '{"amount":10,"country":"KP"}'  # 451 block_restricted_region
curl -s "$B/v1/users/42?role=admin"     # 200, flagged flag_admin_access
curl -s "$B/v1/users/42"                # 200, no match
```

## How it's wired

- `demo-rules.json` (classpath) holds the ruleset; paths are the templated routes.
- `KLSwitchGuard` builds the engine's evaluation input from request parts
  (method, templated path, lowercased headers, query, params, body) and applies a
  simple block policy. It's the JAX-RS analogue of the Node adapter / Phase 13's
  `RequestContext`.
- Resources (`PaymentsResource`, `UsersResource`) call the guard, then a dummy
  service. The engine never interprets actions — that policy lives in the guard.

Same engine, same rules, same decisions as the Rust core and the Node wrapper.

---

## `spring/` — Spring Boot 3

The whole integration is one `@Configuration` class ([`KLSwitchConfig`](spring/src/main/java/com/klswitch/examples/spring/KLSwitchConfig.java)):
a `Client` bean and a filter registration. **No controller imports KLSwitch**
— `PaymentsController` has no annotation, no guard call, nothing.

```java
@Bean
FilterRegistrationBean<KLSwitchFilter> klswitchFilter(
        Client ks, List<HandlerMapping> handlerMappings) {
    var reg = new FilterRegistrationBean<>(
            KLSwitchFilter.withRouteResolver(ks, new SpringRouteResolver(handlerMappings)));
    reg.addUrlPatterns("/*");
    return reg;
}
```

Passing `handlerMappings` is what makes the demo rules match. A filter runs
before `DispatcherServlet` has mapped anything, so without them the engine sees
`/v1/payments/8123` and `block_large_payment` — which selects
`/v1/payments/:id` — never fires. `SpringDemoAppTest` pins exactly that: swap
in a plain `new KLSwitchFilter(ks)` and the block test drops from 429 to 200.

### Tests

```bash
mvn -pl spring test
```

Nine tests booting the real app on a random port over real HTTP — embedded
Tomcat, the real filter chain, the real `DispatcherServlet`. Nothing mocked.
They cover blocking, priority ordering, body replay, `flag` actions read from
the request attribute, manifest discovery, and the one-guard-per-client rule.

### Run it as a real server

```bash
mvn -q -DskipTests package
java -jar spring/target/klswitch-examples-spring-0.0.0.jar   # port 8087
```

```bash
B=localhost:8087
curl -sX POST $B/v1/payments/8123 -H 'content-type: application/json' -d '{"amount":70000}'
# 429 {"ruleId":"block_large_payment","blocked":true,...}

curl -sX POST $B/v1/payments/8123 -H 'content-type: application/json' -d '{"amount":70000,"country":"KP"}'
# 451 {"ruleId":"block_restricted_region","blocked":true,...}   priority 110 wins

curl -sX POST $B/v1/payments/8123 -H 'content-type: application/json' -d '{"amount":10,"country":"GB"}'
# 200 {"ok":true,"country":"GB","amount":10,"paymentId":"8123"}   body replayed to the controller

curl -s "$B/v1/users/7?role=admin"   # 200 {"ok":true,"flags":["flag_admin_access"],"userId":"7"}
curl -s "$B/v1/users/7"              # 200 {"ok":true,"flags":[],"userId":"7"}
```

### Manifest

With `KS_TOKEN` set, the client reports routes via `SpringManifestSource`, which
reads Spring's `@GetMapping`/`@PostMapping` registry. `ServletManifestSource`
would find only the `DispatcherServlet` at `/`, leaving the console's APIs tab
empty.
