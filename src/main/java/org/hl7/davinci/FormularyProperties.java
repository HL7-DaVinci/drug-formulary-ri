package org.hl7.davinci;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Formulary-specific configuration values. These bind against the same
 * "hapi.fhir" prefix as the starter's AppProperties so that existing
 * deployment configuration (application.yaml, ADMIN_TOKEN environment
 * variable) keeps working, while AppProperties stays identical to the
 * upstream starter project.
 */
@Configuration
@ConfigurationProperties(prefix = "hapi.fhir", ignoreUnknownFields = true)
public class FormularyProperties {

  private String adminToken = null;

  public String getAdminToken() {
    return adminToken;
  }

  public void setAdminToken(String adminToken) {
    this.adminToken = adminToken;
  }
}
