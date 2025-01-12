package ca.uhn.fhir.jpa.starter.authorization;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * @basedOn from
 *          https://github.com/carin-alliance/cpcds-server-ri/blob/patient-access/src/main/java/ca/uhn/fhir/jpa/starter/authorization/IntrospectionEndpoint.java
 */
public class IntrospectionEndpoint {
  private IntrospectionEndpoint() {
    throw new IllegalStateException("Introspection Endpoint Utility class");
  }

  public static ResponseEntity<String> handleIntrospection(String token, String serverAddress) {
    JSONObject response = new JSONObject();

    String baseUrl = AuthUtils.getFhirBaseUrl(serverAddress);

    try {
      Algorithm algorithm = Algorithm.RSA256(OauthEndpointController.getPublicKey(), null);
      JWTVerifier verifier = JWT.require(algorithm).withIssuer(baseUrl).withAudience(baseUrl).build();
      DecodedJWT jwt = verifier.verify(token);
      response.put("active", true);
      response.put("aud", jwt.getAudience().get(0));
      response.put("iss", jwt.getIssuer());
      response.put("exp", jwt.getExpiresAt().getTime() / 1000); // Display in sec not ms
      response.put("iat", jwt.getIssuedAt().getTime() / 1000); // Display in sec not ms
      response.put("patient_id", jwt.getClaim("patient_id").asString());
    } catch (JWTVerificationException exception) {
      response.put("active", false);
    }

    return new ResponseEntity<>(response.toString(), HttpStatus.OK);
  }

}
