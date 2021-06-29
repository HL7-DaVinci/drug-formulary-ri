package ca.uhn.fhir.jpa.starter;

import java.util.Calendar;
import java.util.Collections;

import javax.servlet.http.HttpServletRequest;

import ca.uhn.fhir.jpa.provider.r4.JpaConformanceProviderR4;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.IFhirSystemDao;
import ca.uhn.fhir.rest.server.RestfulServer;

public class MetadataProvider extends JpaConformanceProviderR4 {
  MetadataProvider(RestfulServer theRestfulServer, IFhirSystemDao<Bundle, Meta> theSystemDao, DaoConfig theDaoConfig) {
    super(theRestfulServer, theSystemDao, theDaoConfig);
    setCache(false);
  }

  @Override
  public CapabilityStatement getServerConformance(HttpServletRequest theRequest, RequestDetails theRequestDetails) {
    CapabilityStatement metadata = super.getServerConformance(theRequest, theRequestDetails);
    metadata.setTitle("Da Vinci US Drug Formulary Reference Implementation");
    metadata.setStatus(PublicationStatus.DRAFT);
    metadata.setExperimental(true);
    metadata.setPublisher("Da Vinci");

    Calendar calendar = Calendar.getInstance();
    calendar.set(2021, 6, 29, 0, 0, 0);
    metadata.setDate(calendar.getTime());

    CapabilityStatementSoftwareComponent software =
      new CapabilityStatementSoftwareComponent();
    software.setName("https://github.com/HL7-DaVinci/drug-formulary-ri");
    metadata.setSoftware(software);

    metadata.addImplementationGuide("http://build.fhir.org/ig/HL7/davinci-pdex-formulary/index.html");
    metadata.addImplementationGuide("https://wiki.hl7.org/Da_Vinci_PDex-formulary_FHIR_IG_Proposal");

    metadata.setRest(Collections.singletonList(createRestComponent()));

    return metadata;
  }

  private CapabilityStatementRestComponent createRestComponent() {
    // Create Interaction Components
    ResourceInteractionComponent readInteractionComponent = new ResourceInteractionComponent().setCode(TypeRestfulInteraction.READ);
    ResourceInteractionComponent vreadInteractionComponent = new ResourceInteractionComponent().setCode(TypeRestfulInteraction.VREAD);
    ResourceInteractionComponent searchTypeInteractionComponent = new ResourceInteractionComponent().setCode(TypeRestfulInteraction.SEARCHTYPE);
    ResourceInteractionComponent historyTypeInteractionComponent = new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYTYPE);
    ResourceInteractionComponent historyInstanceInteractionComponent = new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYINSTANCE);

    // Create Search Param Components
    CapabilityStatementRestResourceSearchParamComponent idSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    idSearchParamComponent.setDefinition("http://hl7.org/fhir/SearchParameter/Resource-id");
    idSearchParamComponent.setName("_id");
    CapabilityStatementRestResourceSearchParamComponent codeSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    codeSearchParamComponent.setName("code");
    CapabilityStatementRestResourceSearchParamComponent itemSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    itemSearchParamComponent.setName("item");
    CapabilityStatementRestResourceSearchParamComponent statusSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    statusSearchParamComponent.setName("status");
    CapabilityStatementRestResourceSearchParamComponent identifierSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    identifierSearchParamComponent.setName("identifier");
    CapabilityStatementRestResourceSearchParamComponent drugNameSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    drugNameSearchParamComponent.setDefinition("http://hl7.org/fhir/us/davinci-drug-formulary/SearchParameter/DrugName");
    drugNameSearchParamComponent.setName("DrugName");
    CapabilityStatementRestResourceSearchParamComponent drugTierSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    drugTierSearchParamComponent.setDefinition("http://hl7.org/fhir/us/davinci-drug-formulary/SearchParameter/DrugTier");
    drugTierSearchParamComponent.setName("DrugTier");
    CapabilityStatementRestResourceSearchParamComponent drugPlanSearchParamComponent = new CapabilityStatementRestResourceSearchParamComponent();
    drugPlanSearchParamComponent.setDefinition("http://hl7.org/fhir/us/davinci-drug-formulary/SearchParameter/DrugPlan");
    drugPlanSearchParamComponent.setName("DrugPlan");

    // Create MedicationKnowledge Resource
    CapabilityStatementRestResourceComponent medicationKnowledgeResourceComponent = new CapabilityStatementRestResourceComponent();
    medicationKnowledgeResourceComponent.setType("MedicationKnowledge");
    medicationKnowledgeResourceComponent.addSupportedProfile("http://hl7.org/fhir/us/Davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug");
    medicationKnowledgeResourceComponent.addInteraction(readInteractionComponent);
    medicationKnowledgeResourceComponent.addInteraction(vreadInteractionComponent);
    medicationKnowledgeResourceComponent.addInteraction(searchTypeInteractionComponent);
    medicationKnowledgeResourceComponent.addInteraction(historyTypeInteractionComponent);
    medicationKnowledgeResourceComponent.addInteraction(historyInstanceInteractionComponent);
    medicationKnowledgeResourceComponent.addSearchParam(idSearchParamComponent);
    medicationKnowledgeResourceComponent.addSearchParam(codeSearchParamComponent);
    medicationKnowledgeResourceComponent.addSearchParam(drugNameSearchParamComponent);
    medicationKnowledgeResourceComponent.addSearchParam(drugTierSearchParamComponent);
    medicationKnowledgeResourceComponent.addSearchParam(drugPlanSearchParamComponent);

    // Create List Resource
    CapabilityStatementRestResourceComponent listResourceComponent = new CapabilityStatementRestResourceComponent();
    listResourceComponent.setType("List");
    listResourceComponent.addSupportedProfile("http://hl7.org/fhir/us/Davinci-drug-formulary/StructureDefinition/usdf-CoveragePlan");
    listResourceComponent.addInteraction(readInteractionComponent);
    listResourceComponent.addInteraction(vreadInteractionComponent);
    listResourceComponent.addInteraction(searchTypeInteractionComponent);
    listResourceComponent.addInteraction(historyTypeInteractionComponent);
    listResourceComponent.addInteraction(historyInstanceInteractionComponent);
    listResourceComponent.addSearchParam(idSearchParamComponent);
    listResourceComponent.addSearchParam(itemSearchParamComponent);
    listResourceComponent.addSearchParam(statusSearchParamComponent);
    listResourceComponent.addSearchParam(identifierSearchParamComponent);

    // Create new rest component
    CapabilityStatementRestComponent rest = new CapabilityStatementRestComponent();
    rest.setMode(RestfulCapabilityMode.SERVER);
    rest.addResource(listResourceComponent);
    rest.addResource(medicationKnowledgeResourceComponent);

    return rest;
  }
}
