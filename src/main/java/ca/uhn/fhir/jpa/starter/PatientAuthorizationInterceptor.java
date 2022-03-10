package ca.uhn.fhir.jpa.starter;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;

import ca.uhn.fhir.jpa.starter.authorization.OauthEndpointController;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import io.github.cdimascio.dotenv.Dotenv;

@SuppressWarnings("ConstantConditions")
public class PatientAuthorizationInterceptor extends AuthorizationInterceptor {

  private static final Logger logger = ServerLogger.getLogger();
  private static final Dotenv dotenv = Dotenv.load();

  @Override
  public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
    String authHeader = theRequestDetails.getHeader("Authorization");
    if (authHeader != null) {
      // Retrieve the JWT token from the Authorization header
      Pattern pattern = Pattern.compile("Bearer (.*)");
      Matcher matcher = pattern.matcher(authHeader);
      if (matcher.find() && matcher.groupCount() == 1) {
        String token = matcher.group(1);
        logger.fine("AuthorizationInterceptor::Token retrieved is " + token);
        String adminToken = dotenv.get("ADMIN_TOKEN");
        if (adminToken != null && token.equals(adminToken)) {
          logger.info("AuthorizationInterceptor::JWT token is admin token");
          return adminAuthorizedRule();
        }
        try {
          IIdType patientId = verify(token, theRequestDetails.getFhirServerBase());
          if (patientId != null)
            return authorizedRule(patientId);
        } catch (SignatureVerificationException e) {
          String msg = "Authorization failed: invalid signature";
          throw new AuthenticationException(msg, e.getCause());
        } catch (TokenExpiredException e) {
          String msg = "Authorization failed: access token expired";
          throw new AuthenticationException(msg, e.getCause());
        } catch (Exception e) {
          throw new AuthenticationException(e.getMessage(), e.getCause());
        }
      } else {
        throw new AuthenticationException("Authorization header is not in the form 'Bearer <token>'");
      }
    }

    return unauthorizedRule();
  }

  // Set of authorized rules for given scenarios: admin,authorized users and non
  // authorized users

  private List<IAuthRule> adminAuthorizedRule() {
    return new RuleBuilder().allowAll().build();
  }

  private List<IAuthRule> authorizedRule(IIdType userIdPatientId) {
    return new RuleBuilder().allow().read().resourcesOfType("Patient").inCompartment("Patient", userIdPatientId)
        .andThen().allow().read().resourcesOfType("Coverage").inCompartment("Patient", userIdPatientId)
        .andThen().allow().read().resourcesOfType("InsurancePlan").withAnyId()
        .andThen().allow().read().resourcesOfType("Basic").withAnyId()
        .andThen().allow().read().resourcesOfType("MedicationKnowledge").withAnyId()
        .andThen().allow().read().resourcesOfType("Location").withAnyId()
        .andThen().allow().read().resourcesOfType("StructureDefinition").withAnyId()
        .andThen().allow().read().resourcesOfType("CodeSystem").withAnyId()
        .andThen().allow().read().resourcesOfType("ValueSet").withAnyId()
        .andThen().allow().read().resourcesOfType("SearchParameter").withAnyId()
        .andThen().allow().metadata()
        .andThen().denyAll().build();
  }

  private List<IAuthRule> unauthorizedRule() {
    return new RuleBuilder().allow().read().resourcesOfType("InsurancePlan").withAnyId()
        .andThen().allow().read().resourcesOfType("Basic").withAnyId()
        .andThen().allow().read().resourcesOfType("MedicationKnowledge").withAnyId()
        .andThen().allow().read().resourcesOfType("Location").withAnyId()
        .andThen().allow().read().resourcesOfType("StructureDefinition").withAnyId()
        .andThen().allow().read().resourcesOfType("CodeSystem").withAnyId()
        .andThen().allow().read().resourcesOfType("ValueSet").withAnyId()
        .andThen().allow().read().resourcesOfType("SearchParameter").withAnyId()
        .andThen().allow().metadata()
        .andThen().denyAll().build();
  }

  /**
   * Helper method to verify and decode the access token
   * 
   * @param token       - the access token
   * @param fhirBaseUrl - the base url of this FHIR server
   * @return the base interface Patient ID datatype if the jwt token is verified
   *         and contains a patient ID in it claim, otherwise null.
   * @throws SignatureVerificationException
   * @throws TokenExpiredException
   * @throws JWTVerificationException
   */
  private IIdType verify(String token, String fhirBaseUrl)
      throws SignatureVerificationException, TokenExpiredException, JWTVerificationException {
    Algorithm algorithm = Algorithm.RSA256(OauthEndpointController.getPublicKey(), null);
    logger.fine("Verifying JWT token iss and aud is " + fhirBaseUrl);
    JWTVerifier verifier = JWT.require(algorithm).withIssuer(fhirBaseUrl).withAudience(fhirBaseUrl).build();
    DecodedJWT jwt = verifier.verify(token);
    String patientId = jwt.getClaim("patient_id").asString();
    if (patientId != null)
      return new IdType("Patient", patientId);

    return null;
  }

}
