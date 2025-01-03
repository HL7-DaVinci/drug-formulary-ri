package ca.uhn.fhir.jpa.starter.authorization;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.AlgorithmMismatchException;
import com.auth0.jwt.exceptions.InvalidClaimException;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.bcrypt.BCrypt;

import ca.uhn.fhir.jpa.starter.ServerLogger;

public class AuthUtils {
  private AuthUtils() {
    throw new IllegalStateException("Utility class");
  }

  private static final String CLIENT_ID_KEY = "client_id";
  private static final String REDIRECT_URI_KEY = "redirect_uri";

  private static final Logger logger = ServerLogger.getLogger();

  /**
   * Populate DB with default clients and test users
   */
  public static void initializeDB() {
    List<Client> clients = new ArrayList<>();
    List<User> users = new ArrayList<>();

    Client mitreClient = new Client("6cfecf41-e364-44ab-a06f-77f8b0c56c2b",
        "XHNdbHQlOrWXQ8eeXHvZal1EDjI3n2ISlqhtP30Zc89Ad2NuzreoorWQ5P8dPrxtk267SJ23mbxlMzjriAGgkaTnm6Y9f1cOas4Z6xhWXxG43bkIKHhawMR6gGDXAuEWc8wXUHteZIi4YCX6E1qAvGdsXS1KBhkUf1CLcGmauhbCMd73CjMugT527mpLnIebuTp4LYDiJag0usCE6B6fYuTWV21AbvydLnLsMsk83T7aobE4p9R0upL2Ph3OFTE1",
        "https://pdex-formulary-client.org/login");
    Client localhost = new Client("b0c46635-c0b4-448c-a8b9-9bd282d2e05a",
        "bUYbEj5wpazS8Xv1jyruFKpuXa24OGn9MHuZ3ygKexaI5mhKUIzVEBvbv2uggVf1cW6kYD3cgTbCIGK3kjiMcmJq3OG9bn85Fh2x7JKYgy7Jwagdzs0qufgkhPGDvEoVpImpA4clIhfwn58qoTrfHx86ooWLWJeQh4s0StEMqoxLqboywr8u11qmMHd1xwBLehGXUbqpEBlkelBHDWaiCjkhwZeRe4nVu4o8wSAbPQIECQcTjqYBUrBjHlMx5vXU",
        "http://localhost:3000/login");
    clients.add(mitreClient);
    clients.add(localhost);

    User pdexPUser = new User("PDexPatient", BCrypt.hashpw("password", BCrypt.gensalt()), "PDexPatient");
    User admin = new User("admin", BCrypt.hashpw("password", BCrypt.gensalt()), "admin");
    users.add(pdexPUser);
    users.add(admin);

    loadClients(clients);
    loadUsers(users);
  }

  /**
   * Load DB with a list of clients if client does not exist
   */
  public static void loadClients(List<Client> clients) {
    for (Client client : clients) {
      if (Client.getClient(client.getId()) == null) {
        OauthEndpointController.getDB().write(client);
      }
    }
  }

  /**
   * Load DB with a list of clients if user does not exist
   */
  public static void loadUsers(List<User> users) {
    for (User user : users) {
      if (User.getUser(user.getUsername()) == null) {
        OauthEndpointController.getDB().write(user);
      }
    }
  }

  /**
   * Get the FHIR base url from HapiProperties
   * 
   * @return the fhir base url
   */
  public static String getFhirBaseUrl(String serverAddress) {
    String baseUrl = serverAddress;
    if (baseUrl.endsWith("/"))
      return StringUtils.chop(baseUrl);
    else
      return baseUrl;
  }

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

  /**
   * Check if the provided scope is supported
   * 
   * @param String scope - the scope in question
   * @return true if the provided scope is supported, false otherwise
   */
  public static boolean isSupportedScope(String scope) {
    return supportedScopes().contains(scope);
  }

  /**
   * Generate Authorization code for client with a 2 min expiration time
   * 
   * @param baseUrl    - the baseUrl for this server
   * @param clientId   - the client's client_id received in the request
   * @param redirecURI - the client's redirect URI received in the request
   * @param username   - the user's log in username
   * @return a signed JWT token for the authorization code
   */
  public static String generateAuthorizationCode(String baseUrl, String clientId, String redirectURI, String username) {
    try {
      Algorithm algorithm = Algorithm.RSA256(OauthEndpointController.getPublicKey(),
          OauthEndpointController.getPrivateKey());
      Instant expTime = LocalDateTime.now().plusMinutes(2).atZone(ZoneId.systemDefault()).toInstant();
      return JWT.create().withIssuer(baseUrl).withExpiresAt(Date.from(expTime)).withIssuedAt(new Date())
          .withAudience(baseUrl).withClaim(CLIENT_ID_KEY, clientId).withClaim(REDIRECT_URI_KEY, redirectURI)
          .withClaim("username", username).sign(algorithm);
    } catch (Exception e) {
      String msg = String.format("AuthorizationEndpoint::generateAuthorizationCode:Unable to generate code for %s",
          clientId);
      logger.log(Level.SEVERE, msg, e);
      return null;
    }
  }

  /**
   * Produce the client redirect uri with parameters
   * 
   * @param redirectURI - the base client's redirect uri
   * @param attributes  - the parameters to add to the base redirect uri
   * @return formatted redirect uri
   */
  public static String getRedirect(String redirectURI, Map<String, String> attributes) {
    if (attributes.size() > 0) {
      redirectURI += "?";

      int i = 1;
      for (Map.Entry<String, String> entry : attributes.entrySet()) {
        redirectURI += entry.getKey() + "=" + entry.getValue();
        if (i != attributes.size())
          redirectURI += "&";

        i++;
      }
    }
    return redirectURI;
  }

  /**
   * Verify the Basic Authorization header to authenticate the requestor (client)
   * 
   * @param request - the current request
   * @return the clientId from the authorization header if the clientID and secret
   *         provided match the registered ones
   */
  public static String clientIsAuthorized(HttpServletRequest request) {
    String authHeader = request.getHeader("Authorization");
    logger.log(Level.FINE, ("TokenEndpoint::AuthHeader: " + authHeader));
    if (authHeader != null) {
      Pattern pattern = Pattern.compile("Basic (.*)");
      Matcher matcher = pattern.matcher(authHeader);
      if (matcher.find() && matcher.groupCount() == 1) {
        String clientAuthorization = new String(Base64.getDecoder().decode(matcher.group(1)));
        Pattern clientAuthPattern = Pattern.compile("(.*):(.*)");
        Matcher clientAuthMatcher = clientAuthPattern.matcher(clientAuthorization);
        if (clientAuthMatcher.find() && clientAuthMatcher.groupCount() == 2) {
          String clientId = clientAuthMatcher.group(1);
          String clientSecret = clientAuthMatcher.group(2);
          logger.log(Level.FINE,
              ("TokenEndpoint::AuthorizationHeader:client_id " + clientId + " | client_secret " + clientSecret));
          Client client = Client.getClient(clientId);
          if (client != null && client.validateSecret(clientSecret)) {
            logger.log(Level.INFO, ("TokenEndpoint::clientIsAuthorized:client_id " + clientId));
            return clientId;
          }
        }
      }
    }
    logger.warning("TokenEndpoint::clientIsAuthorized: false");
    return null;
  }

  /**
   * Verify the authorization code provided in the POST request's claim to /token
   * path
   * 
   * @param code        - the authorization code provided in the request
   * @param baseUrl     - this server base URL
   * @param redirectURI - the requestor/client redirect URI provided in the POST
   *                    request
   * @param clientId    - the client ID retrieved from the request's Authorization
   *                    Header
   * @return patientId if the authorization code is valid, otherwise null
   */
  public static String authCodeIsValid(String code, String baseUrl, String redirectURI, String clientId) {
    String patientId = null;
    try {
      Algorithm algorithm = Algorithm.RSA256(OauthEndpointController.getPublicKey(), null);
      JWTVerifier verifier = JWT.require(algorithm).withIssuer(baseUrl).withAudience(baseUrl)
          .withClaim(REDIRECT_URI_KEY, redirectURI).withClaim(CLIENT_ID_KEY, clientId).build();
      DecodedJWT jwt = verifier.verify(code);
      String username = jwt.getClaim("username").asString();
      User user = User.getUser(username);
      patientId = user != null ? user.getPatientId() : null;
    } catch (SignatureVerificationException | InvalidClaimException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Signature invalid or claim value invalid",
          e);
    } catch (AlgorithmMismatchException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Algorithm mismatch", e);
    } catch (TokenExpiredException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Token expired", e);
    } catch (JWTVerificationException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Authorization code is invalid: Please obtain a new code", e);
    }
    return patientId;
  }

  /**
   * Verify the refresh token
   * 
   * @param refreshToken - the refresh token
   * @param baseUrl      - this server base url
   * @param clientId     - the requestor/client client id provided in the post
   *                     request Authorization header
   * @return patientId if the refresh token is verified, otherwise null
   */
  public static String refreshTokenIsValid(String refreshToken, String baseUrl, String clientId) {
    String patientId = null;
    try {
      Algorithm algorithm = Algorithm.RSA256(OauthEndpointController.getPublicKey(), null);
      JWTVerifier verifier = JWT.require(algorithm).withIssuer(baseUrl).withAudience(baseUrl)
          .withClaim(CLIENT_ID_KEY, clientId).build();
      DecodedJWT jwt = verifier.verify(refreshToken);
      String jwtId = jwt.getId();
      patientId = jwt.getClaim("patient_id").asString();
      if (!jwtId.equals(OauthEndpointController.getDB().readRefreshToken(patientId))) {
        logger.warning("TokenEndpoint::Refresh token is invalid. Please reauthorize.");
        patientId = null;
      }
    } catch (JWTVerificationException e) {
      logger.log(Level.SEVERE, "TokenEndpoint::Refresh token is invalid. Please reauthorize.", e);
    }
    return patientId;
  }

  /**
   * Generate an access (valid for an hour) or refresh token for the user with
   * correct claims.
   * 
   * @param baseUrl   - this server base url
   * @param clientId  - the client ID of the requestor/client
   * @param patientId - the user's patient ID
   * @param jwtId     - the unique ID for this token
   * @param exp       - the token expiration time
   * 
   * @return access or refresh token for granted user, otherwise null
   */
  public static String generateToken(String baseUrl, String clientId, String patientId, String jwtId,
      Instant exp) {
    try {
      Algorithm algorithm = Algorithm.RSA256(OauthEndpointController.getPublicKey(),
          OauthEndpointController.getPrivateKey());
      return JWT.create().withKeyId(OauthEndpointController.getKeyId()).withIssuer(baseUrl).withAudience(baseUrl)
          .withIssuedAt(new Date()).withExpiresAt(Date.from(exp)).withClaim(CLIENT_ID_KEY, clientId)
          .withClaim("patient_id", patientId)
          .withJWTId(jwtId).sign(algorithm);
    } catch (JWTCreationException e) {
      logger.log(Level.SEVERE,
          "TokenEndpoint::generateToken:Unable to generate token. Invalid signing/claims configuration", e);
    }
    return null;
  }

}
