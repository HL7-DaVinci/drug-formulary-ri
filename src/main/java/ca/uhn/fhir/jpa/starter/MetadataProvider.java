package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

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

    // Remove HAPI defined default OperationDefinitions (Their definitions are not a Canonical URL)
    removeOperations(metadata.getRest());

    metadata.setTitle("Da Vinci US Drug Formulary Reference Implementation");
    metadata.setStatus(PublicationStatus.DRAFT);
    metadata.setExperimental(true);
    metadata.setPublisher("Da Vinci");

    Calendar calendar = Calendar.getInstance();
    calendar.set(2019, 8, 5, 0, 0, 0);
    metadata.setDate(calendar.getTime());

    CapabilityStatementSoftwareComponent software =
      new CapabilityStatementSoftwareComponent();
    software.setName("https://github.com/HL7-DaVinci/drug-formulary-ri");
    metadata.setSoftware(software);

    metadata.addImplementationGuide("https://build.fhir.org/ig/HL7/davinci-pdex-formulary/branches/stu2-draft/index.html");
    metadata.addImplementationGuide("https://wiki.hl7.org/Da_Vinci_PDex-formulary_FHIR_IG_Proposal");

    updateRestComponents(metadata.getRest());

    return metadata;
  }

  private void updateRestComponents(
    List<CapabilityStatementRestComponent> originalRests
  ) {
    for(CapabilityStatementRestComponent rest : originalRests) {
      List<CapabilityStatementRestResourceComponent> resources = rest.getResource();
      for(CapabilityStatementRestResourceComponent resource : resources) {
        List<ResourceInteractionComponent> interactions = new ArrayList<ResourceInteractionComponent>();
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYINSTANCE));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYTYPE));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.READ));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.SEARCHTYPE));
        interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.VREAD));
        resource.setInteraction(interactions);

        if(resource.getType().toString().equals("MedicationKnowledge")){
          resource.addSupportedProfile("http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug");
        } else if(resource.getType().toString().equals("Basic")){
          resource.addSupportedProfile("http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyItem");
        } else if(resource.getType().toString().equals("InsurancePlan")){
          resource.addSupportedProfile("http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-PayerInsurancePlan");
          resource.addSupportedProfile("http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-InsuranceDrugPlan");
        } else if(resource.getType().toString().equals("Location")){
          resource.addSupportedProfile("http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-InsurancePlanLocation");
        }

      }
    }
  }
  
  private void removeOperations(
    List<CapabilityStatementRestComponent> originalRests
  ) {
    for(CapabilityStatementRestComponent rest : originalRests) {
      rest.setOperation(null);
    }
  }
}

