package ca.uhn.fhir.jpa.starter.authorization;

import java.util.Arrays;
import java.util.List;

public class AuthUtils {

  /**
   * Get supported auth response types
   * 
   * @return String[] of supported response types
   */
  public static List<String> authResponseType() {
    String[] responseTypes = { "code", "refresh_token" };
    return Arrays.asList(responseTypes);
  }

  /**
   * http://hl7.org/fhir/smart-app-launch/conformance/index.html#core-capabilities
   * Get the array of all core capabilities
   * 
   * @return String[] of core capabilities
   */
  public static List<String> coreCapabilities() {
    String[] capabilities = { "launch-standalone", "client-confidential-symmetric",
        "context-standalone-patient", "permission-patient", "permission-user" };
    return Arrays.asList(capabilities);
  }

  /**
   * Get the array of all supported scopes
   * 
   * @return String[] of supported scopes
   */
  public static List<String> supportedScopes() {
    String[] scopes = { "patient/*.read", "user/*.read", "offline_access", "launch/patient", "openid", "fhirUser" };
    return Arrays.asList(scopes);
  }
}
