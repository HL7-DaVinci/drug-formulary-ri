package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import ca.uhn.fhir.test.utilities.JettyUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExampleServerR4IT {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ExampleServerR4IT.class);
  private static IGenericClient ourClient;
  private static FhirContext ourCtx;
  private static int ourPort;
  private static Server ourServer;

  static {
    HapiProperties.forceReload();
    HapiProperties.setProperty(HapiProperties.DATASOURCE_URL, "jdbc:h2:mem:dbr4");
    HapiProperties.setProperty(HapiProperties.FHIR_VERSION, "R4");
    HapiProperties.setProperty(HapiProperties.SUBSCRIPTION_WEBSOCKET_ENABLED, "true");
    ourCtx = FhirContext.forR4();
  }

  @Test
  public void getMetadata() throws Exception {
    // Test GET /fhir/metadata
    CapabilityStatement metadata = ourClient.capabilities().ofType(CapabilityStatement.class).execute();
    assertNotNull(metadata);

    List<String> checkTypes = Arrays.asList("InsurancePlan", "Basic", "MedicationKnowledge", "Location");
    List<CapabilityStatementRestResourceComponent> resources = metadata.getRestFirstRep().getResource();
    List<String> resourceTypes = resources.stream().map(c -> c.getType()).collect(Collectors.toList());
    assertTrue(resourceTypes.containsAll(checkTypes));
  }

  @Test
  public void getInsurancePlans() throws Exception {
    // Test GET /fhir/InsurancePlan
    Bundle bundle = ourClient.search().byUrl("/InsurancePlan").returnBundle(Bundle.class).execute();
    assertNotNull(bundle);
  }

  @Test
  public void getBasics() throws Exception {
    // Test GET /fhir/Basic
    Bundle bundle = ourClient.search().byUrl("/Basic").returnBundle(Bundle.class).execute();
    assertNotNull(bundle);
  }

  @Test
  public void getMedicationKnowledge() throws Exception {
    // Test GET /fhir/MedicationKnowledge
    Bundle bundle = ourClient.search().byUrl("/MedicationKnowledge").returnBundle(Bundle.class).execute();
    assertNotNull(bundle);
  }

  @Test
  public void getLocations() throws Exception {
    // Test GET /fhir/InsurancePlan
    Bundle bundle = ourClient.search().byUrl("/Location").returnBundle(Bundle.class).execute();
    assertNotNull(bundle);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    ourServer.stop();
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    String path = Paths.get("").toAbsolutePath().toString();

    ourLog.info("Project base path is: {}", path);

    ourServer = new Server(0);

    WebAppContext webAppContext = new WebAppContext();
    webAppContext.setContextPath("/hapi-fhir-jpaserver");
    webAppContext.setDisplayName("HAPI FHIR");
    webAppContext.setDescriptor(path + "/src/main/webapp/WEB-INF/web.xml");
    webAppContext.setResourceBase(path + "/target/hapi-fhir-jpaserver-starter");
    webAppContext.setParentLoaderPriority(true);

    ourServer.setHandler(webAppContext);
    ourServer.start();

    ourPort = JettyUtil.getPortForStartedServer(ourServer);

    ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
    String ourServerBase = HapiProperties.getServerAddress();
    ourServerBase = "http://localhost:" + ourPort + "/hapi-fhir-jpaserver/fhir/";

    ourClient = ourCtx.newRestfulGenericClient(ourServerBase);
    ourClient.registerInterceptor(new LoggingInterceptor(true));
  }

  public static void main(String[] theArgs) throws Exception {
    ourPort = 8080;
    beforeClass();
  }
}
