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

    // Remove HAPI defined default OperationDefinitions (Their definitions are not
    // Canonical URL)
    removeOperations(metadata.getRest());

    metadata.addInstantiates("http://hl7.org/fhir/us/davinci-drug-formulary/CapabilityStatement/usdf-server");
    metadata.setName("PDEx Formulary RI");
    metadata.setTitle("Da Vinci US Drug Formulary Reference Implementation");
    metadata.setStatus(PublicationStatus.DRAFT);
    metadata.setExperimental(true);
    metadata.setPublisher("Da Vinci");

    Calendar calendar = Calendar.getInstance();
    calendar.set(2022, 7, 10, 0, 0, 0); // Change this date each time the capability statement is being updated
    metadata.setDate(calendar.getTime());

    CapabilityStatementSoftwareComponent software = new CapabilityStatementSoftwareComponent();
    software.setName("https://github.com/HL7-DaVinci/drug-formulary-ri");
    metadata.setSoftware(software);

    metadata.addImplementationGuide(
        "http://hl7.org/fhir/us/davinci-drug-formulary/ImplementationGuide/hl7.fhir.us.davinci-drug-formulary");
    metadata.setVersion("2.0.0");

    updateRestComponents(metadata.getRest());

    return metadata;
  }

  private void updateRestComponents(List<CapabilityStatementRestComponent> originalRests) {
    CodeableConcept service = getSecurityComponentService();
    Extension oauthExtension = getSecurityComponentOauthExtension();
    CapabilityStatementRestSecurityComponent securityComponent = getSecurityComponent(service, oauthExtension);

    for (CapabilityStatementRestComponent rest : originalRests) {
      rest.setSecurity(securityComponent);
      List<CapabilityStatementRestResourceComponent> resources = rest.getResource();
      for (CapabilityStatementRestResourceComponent resource : resources) {
        configResourceComponent(resource);
      }
    }
  }

  private void removeOperations(
      List<CapabilityStatementRestComponent> originalRests) {
    for (CapabilityStatementRestComponent rest : originalRests) {
      rest.setOperation(null);
    }
  }

  // Set rest security component instance
  private CapabilityStatementRestSecurityComponent getSecurityComponent(CodeableConcept service,
      Extension oauthExtension) {
    CapabilityStatementRestSecurityComponent securityComponent = new CapabilityStatementRestSecurityComponent();
    securityComponent.addService(service);
    securityComponent.addExtension(oauthExtension);
    return securityComponent;
  }

  // Security Component Properties
  private CodeableConcept getSecurityComponentService() {
    CodeableConcept service = new CodeableConcept();
    ArrayList<Coding> codings = new ArrayList<>();
    codings.add(
        new Coding("http://terminology.hl7.org/CodeSystem/restful-security-service", "SMART-on-FHIR", "SMART on FHIR"));
    service.setCoding(codings);
    service.setText("OAuth2 using SMART-on-FHIR profile (see http://docs.smarthealthit.org)");
    return service;
  }

  private Extension getSecurityComponentOauthExtension() {
    Extension oauthExtension = new Extension();
    ArrayList<Extension> uris = new ArrayList<>();
    uris.add(new Extension("authorize", new UriType(getAuthorizationUrl())));
    uris.add(new Extension("introspect", new UriType(getIntrospectionUrl())));
    uris.add(new Extension("token", new UriType(getTokenUrl())));
    oauthExtension.setUrl("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
    oauthExtension.setExtension(uris);
    return oauthExtension;
  }

  // Resources configuration
  private void configResourceComponent(CapabilityStatementRestResourceComponent resource) {
    setCommonResourceProperties(resource);

    switch (resource.getType()) {
      case "MedicationKnowledge":
        setMedicationKnowledgeResourceProperties(resource);
        break;
      case "Basic":
        setBasicResourceProperties(resource);
        break;
      case "InsurancePlan":
        setInsurancePlanResourceProperties(resource);
        break;
      case "Location":
        setLocationResourceProperties(resource);
        break;
      case "Patient":
        setPatientResourceProperties(resource);
        break;
      case "Coverage":
        setCoverageResourceProperties(resource);
        break;
      case "Organization":
        setOrganizationResourceProperties(resource);
        break;
      default:
        break;
    }
  }

  private void setCommonResourceProperties(CapabilityStatementRestResourceComponent resource) {
    List<ResourceInteractionComponent> interactions = getInteractionList();
    resource.setInteraction(interactions);
    resource.addReferencePolicy(ReferenceHandlingPolicy.RESOLVES);
    resource.addSearchParam(lastUpdatedParam());
  }

  private void setInsurancePlanResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-PayerInsurancePlan");
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-Formulary");
    resource.addSearchParam(periodParam());
    resource.addSearchParam(coverageAreaParam());
    resource.addSearchParam(formularyCoverageParam());
    resource.addSearchInclude("InsurancePlan:formulary-coverage");
  }

  private void setMedicationKnowledgeResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug");
  }

  private void setBasicResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyItem");
    resource.addSearchParam(periodParam());
    resource.addSearchParam(statusParam());
    resource.addSearchParam(formularyParam());
    resource.addSearchInclude("Basic:formulary");
  }

  private void setLocationResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-InsurancePlanLocation");
  }

  private void setPatientResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient");
  }

  private void setCoverageResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile("https://hl7.org/fhir/us/carin-bb/STU1.1/StructureDefinition/C4BB-Coverage");
  }

  private void setOrganizationResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Organization");
  }

  // Interaction component for all resources:
  private List<ResourceInteractionComponent> getInteractionList() {
    List<ResourceInteractionComponent> interactions = new ArrayList<>();
    interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYINSTANCE));
    interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYTYPE));
    interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.READ));
    interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.SEARCHTYPE));
    interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.VREAD));
    return interactions;
  }

  // All resources search params
  private CapabilityStatementRestResourceSearchParamComponent lastUpdatedParam() {
    CapabilityStatementRestResourceSearchParamComponent lastUpdated = new CapabilityStatementRestResourceSearchParamComponent();
    lastUpdated.setName("_lastUpdated").setType(SearchParamType.DATE)
        .setDocumentation("Select resources based on the last time they were changed");
    return lastUpdated;
  }

  // InsurancePlan and Basic Search Params
  private CapabilityStatementRestResourceSearchParamComponent periodParam() {
    CapabilityStatementRestResourceSearchParamComponent period = new CapabilityStatementRestResourceSearchParamComponent();
    period.setName("period").setType(SearchParamType.DATE)
        .setDocumentation("Accesses the active period of the resource instance");
    return period;
  }

  // InsurancePlan Search Params
  private CapabilityStatementRestResourceSearchParamComponent coverageAreaParam() {
    CapabilityStatementRestResourceSearchParamComponent coverageArea = new CapabilityStatementRestResourceSearchParamComponent();
    coverageArea.setName("coverage-area").setType(SearchParamType.REFERENCE)
        .setDocumentation("Search InsurancePlan by coverage location");
    return coverageArea;
  }

  private CapabilityStatementRestResourceSearchParamComponent formularyCoverageParam() {
    CapabilityStatementRestResourceSearchParamComponent formularyCoverage = new CapabilityStatementRestResourceSearchParamComponent();
    formularyCoverage.setName("formulary-coverage").setType(SearchParamType.REFERENCE)
        .setDocumentation("Accesses the Coverage Formulary Reference of an InsurancePlan");
    return formularyCoverage;
  }

  // Basic Search Params
  private CapabilityStatementRestResourceSearchParamComponent statusParam() {
    CapabilityStatementRestResourceSearchParamComponent status = new CapabilityStatementRestResourceSearchParamComponent();
    status.setName("status").setType(SearchParamType.TOKEN)
        .setDocumentation("Accesses the status of the given resource instance");
    return status;
  }

  private CapabilityStatementRestResourceSearchParamComponent formularyParam() {
    CapabilityStatementRestResourceSearchParamComponent formulary = new CapabilityStatementRestResourceSearchParamComponent();
    formulary.setName("formulary").setType(SearchParamType.REFERENCE)
        .setDocumentation("Accesses the formulary reference of the given resource");
    return formulary;
  }
}
