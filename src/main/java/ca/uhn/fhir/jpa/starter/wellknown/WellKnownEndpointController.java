package ca.uhn.fhir.jpa.starter.wellknown;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import ca.uhn.fhir.jpa.starter.MetadataProvider;
import ca.uhn.fhir.jpa.starter.authorization.AuthUtils;

@RestController
public class WellKnownEndpointController {
  private static final String WELL_KNOWN_AUTHORIZATION_ENDPOINT = "authorization_endpoint";
  private static final String WELL_KNOWN_TOKEN_ENDPOINT = "token_endpoint";
  private static final String WELL_KNOWN_REGISTRATION_ENDPOINT = "registration_endpoint";
  private static final String WELL_KNOWN_INTROSPECTION_ENDPOINT = "introspection_endpoint";
  private static final String WELL_KNOWN_SUPPORTED_AUTH_METHODS = "token_endpoint_auth_methods_supported";
  private static final String WELL_KNOWN_RESPONSE_TYPES = "response_types_supported";
  private static final String WELL_KNOWN_CAPABILITIES = "capabilities";
  private static final String WELL_KNOWN_SCOPES_SUPPORTED = "scopes_supported";

  private static final JSONArray WELL_KNOWN_CAPABILITIES_VALUES = new JSONArray(AuthUtils.coreCapabilities());

  private static final String[] authMethodValues = { "client_secret_basic" };
  private static final JSONArray WELL_KNOWN_SUPPORTED_AUTH_METHODS_VALUES = new JSONArray(authMethodValues);

  private static final JSONArray WELL_KNOWN_RESPONSE_TYPE_VALUES = new JSONArray(AuthUtils.authResponseType());

  private static final JSONArray WELL_KNOWN_SCOPES_SUPPORTED_VALUES = new JSONArray(AuthUtils.supportedScopes());

  @PostConstruct
  protected void postConstruct() {
    System.out.println("Well Known controller added.");
  }

  /**
   * Get request to support well-known endpoints for authorization metadata. See
   * http://www.hl7.org/fhir/smart-app-launch/conformance/index.html#using-well-known
   *
   * @return String representing json object of metadata returned at this url
   * @throws IOException when the request fails
   */
  @GetMapping(path = "/smart-configuration", produces = { "application/json" })
  public String getWellKnownJson(HttpServletRequest theRequest) {

    JSONObject wellKnownJson = new JSONObject();
    wellKnownJson.put(WELL_KNOWN_AUTHORIZATION_ENDPOINT, MetadataProvider.getAuthorizationUrl());
    wellKnownJson.put(WELL_KNOWN_TOKEN_ENDPOINT, MetadataProvider.getTokenUrl());
    wellKnownJson.put(WELL_KNOWN_REGISTRATION_ENDPOINT, MetadataProvider.getRegisterUrl());
    wellKnownJson.put(WELL_KNOWN_INTROSPECTION_ENDPOINT, MetadataProvider.getIntrospectionUrl());
    wellKnownJson.put(WELL_KNOWN_SUPPORTED_AUTH_METHODS, WELL_KNOWN_SUPPORTED_AUTH_METHODS_VALUES);
    wellKnownJson.put(WELL_KNOWN_RESPONSE_TYPES, WELL_KNOWN_RESPONSE_TYPE_VALUES);
    wellKnownJson.put(WELL_KNOWN_CAPABILITIES, WELL_KNOWN_CAPABILITIES_VALUES);
    wellKnownJson.put(WELL_KNOWN_SCOPES_SUPPORTED, WELL_KNOWN_SCOPES_SUPPORTED_VALUES);

    return wellKnownJson.toString(2);
  }
}
