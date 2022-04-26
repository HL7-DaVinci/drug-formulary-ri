package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import ca.uhn.fhir.jpa.provider.r4.JpaConformanceProviderR4;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.Enumerations.SearchParamType;

import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.IFhirSystemDao;
import ca.uhn.fhir.rest.server.RestfulServer;

public class MetadataProvider extends JpaConformanceProviderR4 {
  MetadataProvider(RestfulServer theRestfulServer, IFhirSystemDao<Bundle, Meta> theSystemDao, DaoConfig theDaoConfig) {
    super(theRestfulServer, theSystemDao, theDaoConfig);
  }

  public static String getAuthorizationUrl() {
    return HapiProperties.getServerAddress() + "oauth/authorization";
  }

  public static String getIntrospectionUrl() {
    return HapiProperties.getServerAddress() + "oauth/introspect";
  }

  public static String getRegisterUrl() {
    return HapiProperties.getServerAddress() + "oauth/register/client";
  }

  public static String getTokenUrl() {
    return HapiProperties.getServerAddress() + "oauth/token";
  }

  @Override
  public CapabilityStatement getServerConformance(HttpServletRequest theRequest, RequestDetails theRequestDetails) {
    CapabilityStatement metadata = super.getServerConformance(theRequest, theRequestDetails);

    // Remove HAPI defined default OperationDefinitions (Their definitions are not a
    // Canonical URL)
    removeOperations(metadata.getRest());

    metadata.addInstantiates("http://hl7.org/fhir/us/davinci-drug-formulary/CapabilityStatement/usdf-server");
    metadata.setTitle("Da Vinci US Drug Formulary Reference Implementation");
    metadata.setStatus(PublicationStatus.DRAFT);
    metadata.setExperimental(true);
    metadata.setPublisher("Da Vinci");

    Calendar calendar = Calendar.getInstance();
    calendar.set(2022, 3, 5, 0, 0, 0);
    metadata.setDate(calendar.getTime());

    CapabilityStatementSoftwareComponent software = new CapabilityStatementSoftwareComponent();
    software.setName("https://github.com/HL7-DaVinci/drug-formulary-ri");
    metadata.setSoftware(software);

    metadata
        .addImplementationGuide(
            "http://hl7.org/fhir/us/davinci-drug-formulary/ImplementationGuide/hl7.fhir.us.davinci-drug-formulary");
    metadata.addImplementationGuide("https://wiki.hl7.org/Da_Vinci_PDex-formulary_FHIR_IG_Proposal");
    metadata.setVersion("1.2.0");

    updateRestComponents(metadata.getRest());

    return metadata;
  }

  private void updateRestComponents(
      List<CapabilityStatementRestComponent> originalRests) {
    CodeableConcept service = new CodeableConcept();
    ArrayList<Coding> codings = new ArrayList<>();
    codings.add(new Coding("http://hl7.org/fhir/restful-security-service", "SMART-on-FHIR", "SMART on FHIR"));
    service.setCoding(codings);
    service.setText("OAuth2 using SMART-on-FHIR profile (see http://docs.smarthealthit.org)");

    Extension oauthExtension = new Extension();
    ArrayList<Extension> uris = new ArrayList<>();
    uris.add(new Extension("authorize", new UriType(getAuthorizationUrl())));
    uris.add(new Extension("introspect", new UriType(getIntrospectionUrl())));
    uris.add(new Extension("token", new UriType(getTokenUrl())));
    oauthExtension.setUrl("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
    oauthExtension.setExtension(uris);

    CapabilityStatementRestSecurityComponent securityComponent = new CapabilityStatementRestSecurityComponent();
    securityComponent.addService(service);
    securityComponent.addExtension(oauthExtension);

    for (CapabilityStatementRestComponent rest : originalRests) {
      rest.setSecurity(securityComponent);
      List<CapabilityStatementRestResourceComponent> resources = rest.getResource();
      for (CapabilityStatementRestResourceComponent resource : resources) {
        List<ResourceInteractionComponent> interactions = new ArrayList<>();
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYINSTANCE));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYTYPE));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.READ));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.SEARCHTYPE));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.VREAD));
        resource.setInteraction(interactions);
        resource.addReferencePolicy(ReferenceHandlingPolicy.RESOLVES);

        CapabilityStatementRestResourceSearchParamComponent lastUpdated = new CapabilityStatementRestResourceSearchParamComponent();
        lastUpdated.setName("_lastUpdated").setType(SearchParamType.DATE)
            .setDocumentation("Select resources based on the last time they were changed");
        resource.addSearchParam(lastUpdated);
        /**
         * TODO:
         * 1) add a method to set a defineed set of search param and search include for
         * each supported resource
         * 2) seed the DB with bundle of SearchParameter
         */

        switch (resource.getType()) {
          case "MedicationKnowledge":
            resource.addSupportedProfile(
                "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug");
            break;
          case "Basic":
            resource.addSupportedProfile(
                "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyItem");
            break;
          case "InsurancePlan":
            resource.addSupportedProfile(
                "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-PayerInsurancePlan");
            resource.addSupportedProfile(
                "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-InsuranceDrugPlan");
            break;
          case "Location":
            resource.addSupportedProfile(
                "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-InsurancePlanLocation");
            break;
          case "Patient":
            resource.addSupportedProfile("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient");
            break;
          case "Coverage":
            resource.addSupportedProfile("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Coverage");
            break;
          case "Organization":
            resource.addSupportedProfile("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Organization");
            break;
          default:
            break;
        }

      }
    }
  }

  private void removeOperations(
      List<CapabilityStatementRestComponent> originalRests) {
    for (CapabilityStatementRestComponent rest : originalRests) {
      rest.setOperation(null);
    }
  }
}
