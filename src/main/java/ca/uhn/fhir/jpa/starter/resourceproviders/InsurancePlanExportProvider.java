package ca.uhn.fhir.jpa.starter.resourceproviders;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.jobs.export.BulkDataExportProvider;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.ServerLogger;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class InsurancePlanExportProvider implements IResourceProvider {

    private static final Logger logger = ServerLogger.getLogger();

    private DaoRegistry daoRegistry;

    private IJobCoordinator jobCoordinator;

    private FhirContext myFhirContext;

    private BulkDataExportProvider bulkDataExportProvider;

    public InsurancePlanExportProvider(DaoRegistry daoRegistry, IJobCoordinator jobCoordinator, FhirContext fhirContext, BulkDataExportProvider bulkDataExportProvider) {
        this.daoRegistry = daoRegistry;
        this.jobCoordinator = jobCoordinator;
        this.myFhirContext = fhirContext;
        this.bulkDataExportProvider = bulkDataExportProvider;
    }

    @Override
    public Class<InsurancePlan> getResourceType() { return InsurancePlan.class; }

    @Operation(name = "$export", idempotent = true, manualResponse = true)
    public void insurancePlanExport(ServletRequestDetails theRequestDetails) {

        IPrimitiveType<String> theType = new StringType("InsurancePlan,Location,Basic,MedicationKnowledge");

        bulkDataExportProvider.export(null, theType, null , null, null, null, theRequestDetails);
    }

    @Operation(name = "$export", idempotent = true, manualResponse = true)
    public void insurancePlanInstanceExport(@IdParam IIdType insurancePlanId, ServletRequestDetails theRequestDetails) {

        // require "Prefer: respond-async" request header
        bulkDataExportProvider.validatePreferAsyncHeader(theRequestDetails, "$export");

        // validate if resource exists. Throws ResourceNotFoundException if doesn't exist
        InsurancePlan insurancePlan = daoRegistry.getResourceDao(InsurancePlan.class).read(new IdType(insurancePlanId.getIdPart()));

        String medicationIds = "";
        String locationIds = "";
        List<IPrimitiveType<String>> theType = new ArrayList<>();
        theType.add(new StringType("InsurancePlan"));

        // pulling referenced medication ids to pass to MedicationKnowledge type filters.
        SearchParameterMap searchParameterMap = new SearchParameterMap();
        searchParameterMap.add("formulary", new ReferenceParam("InsurancePlan/"+insurancePlanId.getIdPart()));

        IBundleProvider basicBundle = daoRegistry.getResourceDao(Basic.class).search(searchParameterMap);

        if( insurancePlan.hasCoverageArea() ){
            locationIds = insurancePlan.getCoverageArea().stream().map(reference -> reference.getReference()).collect(Collectors.joining(","));
        }
        List<IPrimitiveType<String>> theTypeFilter = new ArrayList<>();
        theTypeFilter.add(new StringType("InsurancePlan?_id="+insurancePlanId.getIdPart()));
        if( basicBundle != null && basicBundle.size() > 0 ){
            theType.add(new StringType("Basic"));
            theTypeFilter.add(new StringType("Basic?formulary="+insurancePlanId));
            List<IBaseResource> basicResource= basicBundle.getResources(0, basicBundle.size());
            medicationIds = basicResource.stream().filter(resource -> resource instanceof Basic)
                    .map(resource -> (Basic)resource)
                    .map(Basic::getSubject)
                    .filter(subject -> subject.hasReference())
                    .map(subject -> subject.getReference())
                    .collect(Collectors.joining(","));
        }
        if( medicationIds != null && !medicationIds.isEmpty() ){
            theType.add(new StringType("MedicationKnowledge"));
            theTypeFilter.add(new StringType("MedicationKnowledge?_id="+medicationIds));
        }
        if( locationIds != null && !locationIds.isEmpty() ){
            theType.add(new StringType("Location"));
            theTypeFilter.add(new StringType("Location?_id="+locationIds));
        }
        String theTypeString = theType.stream().map(IPrimitiveType::getValue).collect(Collectors.joining(","));

        bulkDataExportProvider.export(null, new StringType(theTypeString), null , theTypeFilter, null, null, theRequestDetails);
    }
}