package ca.uhn.fhir.jpa.starter;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class to enable component scanning for custom server components.
 * This scans the org.hl7.davinci package to pick up custom controllers,
 * providers, interceptors, etc.
 */
@Configuration
@ComponentScan(basePackages = {"org.hl7.davinci"})
public class CustomServerConfig {
    // Any additional custom configuration should be handled in the custom package.
    // This class should only be edited if absolutely needed.
}
