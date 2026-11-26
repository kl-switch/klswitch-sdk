package com.klswitch.examples.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot counterpart to the Dropwizard demo, exercising the SDK's Spring
 * route resolver against a real {@code DispatcherServlet}.
 *
 * <pre>
 * mvn -pl spring -am package
 * java -jar spring/target/klswitch-examples-spring-0.0.0.jar   # port 8087
 * </pre>
 *
 * <p>Both servers load the same {@code demo-rules.json}, so a rule that blocks
 * on Dropwizard blocks here too.
 */
@SpringBootApplication
public class SpringDemoApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringDemoApp.class, args);
    }
}
