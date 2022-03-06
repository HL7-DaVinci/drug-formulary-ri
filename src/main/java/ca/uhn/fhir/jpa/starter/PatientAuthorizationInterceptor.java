package ca.uhn.fhir.jpa.starter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.hl7.fhir.r4.model.IdType;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;

@SuppressWarnings("ConstantConditions")
public class PatientAuthorizationInterceptor extends AuthorizationInterceptor {

  @Override
  public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
    String requestPath = theRequestDetails.getRequestPath();
    // Any user can read the InsurancePlan, Basic, and MedicationKnowledge resources
    // regardless of auth.
    if (!requestPath.startsWith("Patient") && !requestPath.startsWith("Coverage"))
      return new RuleBuilder().allow().read().resourcesOfType("InsurancePlan").withAnyId()
          .andThen().allow().read().resourcesOfType("Basic").withAnyId()
          .andThen().allow().read().resourcesOfType("MedicationKnowledge")
          .withAnyId().andThen().denyAll().build();

    IdType userIdPatientId = null;
    boolean userIsAdmin = false;
    String resourceType = "Patient";
    String authHeader = theRequestDetails.getHeader("Authorization");
    String regex = "Bearer (.*)";
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = pattern.matcher(authHeader);
    String token = "";
    if (matcher.find() && matcher.groupCount() == 1)
      token = matcher.group(1);
    else
      throw new AuthenticationException("Authorization header is not in the form \"Bearer <token>\"");

    try {
      // Verify and decode the JWT token
      Algorithm algorithm = Algorithm.HMAC256("secret");
      JWTVerifier verifier = JWT.require(algorithm).withIssuer("drug-formulary-ri").build();
      DecodedJWT jwt = verifier.verify(token);
      String patientId = jwt.getClaim("patient").asString();

      // Set the userIdPatientId based on the token
      if (patientId.equals("admin"))
        userIsAdmin = true;
      else
        userIdPatientId = new IdType(resourceType, patientId);
    } catch (JWTVerificationException exception) {
      // Invalid signature/claims
      throw new AuthenticationException("Authorization failed: " + exception.getMessage());
    }

    // If the user is a specific patient, we create the following rule chain:
    // Allow the user to read anything in their own patient compartment and other
    // publicly available resources (InsurancePlan, Basic & MedicationKnowledge)
    // If a client request doesn't pass either of the above, deny it
    if (userIdPatientId != null) {
      return new RuleBuilder().allow().read().allResources()
          .inCompartment(resourceType, userIdPatientId).andThen()
          .allow().read().resourcesOfType("InsurancePlan").withAnyId()
          .andThen().allow().read().resourcesOfType("Basic").withAnyId()
          .andThen().allow().read().resourcesOfType("MedicationKnowledge").withAnyId()
          .andThen().allow().metadata()
          .andThen().denyAll().build();
    }

    // If the user is an admin, allow everything
    if (userIsAdmin) {
      return new RuleBuilder().allowAll().build();
    }

    // By default, deny everything. This should never get hit, but it's
    // good to be defensive
    return new RuleBuilder().allow().metadata().andThen().denyAll().build();

  }

}
