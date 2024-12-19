package ca.uhn.fhir.jpa.starter.interceptors;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.AppProperties;
import jakarta.interceptor.Interceptor;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.Enumerations.SearchParamType;

@Interceptor
public class MetadataProvider {
  private AppProperties appProperties;

  public MetadataProvider(AppProperties appProperties) {
    this.appProperties = appProperties;
  }

  public String getAuthorizationUrl() {
    return appProperties.getServer_address() + "oauth/authorization";
  }

  public String getIntrospectionUrl() {
    return appProperties.getServer_address() + "oauth/introspect";
  }

  public String getRegisterUrl() {
    return appProperties.getServer_address() + "oauth/register/client";
  }

  public String getTokenUrl() {
    return appProperties.getServer_address() + "oauth/token";
  }

  @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
  public IBaseConformance getServerConformance( IBaseConformance theCapabilityStatement ) {
    CapabilityStatement metadata = (CapabilityStatement) theCapabilityStatement;

    // Remove HAPI defined default OperationDefinitions (Their definitions are not
    // Canonical URL)
    //removeOperations(metadata.getRest());

    metadata.addInstantiates("http://hl7.org/fhir/us/davinci-drug-formulary/CapabilityStatement/usdf-server");
    metadata.setName("PDEx Formulary RI");
    metadata.setTitle("Da Vinci US Drug Formulary Reference Implementation");
    metadata.setStatus(PublicationStatus.DRAFT);
    metadata.setExperimental(true);
    metadata.setPublisher("Da Vinci");
    metadata.getImplementation().setDescription("Da Vinci Drug Formulary Reference Server");
    metadata.getFormat().clear();
    metadata.addFormat("application/fhir+xml");
    metadata.addFormat("application/fhir+json");
    metadata.getPatchFormat().clear();
    Calendar calendar = Calendar.getInstance();
    calendar.set(2022, 7, 10, 0, 0, 0); // Change this date each time the capability statement is being updated
    metadata.setDate(calendar.getTime());

    metadata.getSoftware().setName("https://github.com/HL7-DaVinci/drug-formulary-ri");

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
        rest.getOperation().clear();
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
        new Coding("http://terminology.hl7.org/CodeSystem/restful-security-service", "SMART-on-FHIR", "SMART-on-FHIR"));
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
    resource.setConditionalDelete(ConditionalDeleteStatus.SINGLE);
    resource.getInteraction().clear();
    resource.setInteraction(interactions);
    resource.addReferencePolicy(ReferenceHandlingPolicy.RESOLVES);
    resource.getOperation().clear();
  }

  private void setInsurancePlanResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-PayerInsurancePlan");
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-Formulary");
    resource.addSearchInclude("InsurancePlan:formulary-coverage");
  }

  private void setMedicationKnowledgeResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug");
  }

  private void setBasicResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyItem");
    resource.addSearchInclude("Basic:formulary");
  }

  private void setLocationResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile(
        "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-InsurancePlanLocation");
    resource.addSearchParam(lastUpdatedParam());
  }

  private void setPatientResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient");
  }

  private void setCoverageResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile("https://hl7.org/fhir/us/carin-bb/STU1.1/StructureDefinition/C4BB-Coverage");
    resource.addSearchParam(lastUpdatedParam());
  }

  private void setOrganizationResourceProperties(CapabilityStatementRestResourceComponent resource) {
    resource.addSupportedProfile("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Organization");
    resource.addSearchParam(lastUpdatedParam());
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
}
