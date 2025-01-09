package ca.uhn.fhir.jpa.starter.resourceproviders;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.api.JobOperationResultJson;
import ca.uhn.fhir.batch2.jobs.export.BulkDataExportProvider;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.batch2.model.JobInstanceStartRequest;
import ca.uhn.fhir.batch2.model.StatusEnum;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.BulkExportJobResults;
import ca.uhn.fhir.jpa.batch.models.Batch2JobStartResponse;
import ca.uhn.fhir.jpa.bulk.export.model.BulkExportResponseJson;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.ServerLogger;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.bulk.BulkExportJobParameters;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.JsonUtil;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import ca.uhn.fhir.util.UrlUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.*;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class InsurancePlanExportProvider implements IResourceProvider {

    public static final Set<String> DEFAULT_RESOURCE_TYPES = Set.of("InsurancePlan", "Basic", "MedicationKnowledge","Location");

    private static final Logger logger = ServerLogger.getLogger();

    private DaoRegistry daoRegistry;

    private IJobCoordinator jobCoordinator;

    private FhirContext myFhirContext;

    public InsurancePlanExportProvider(DaoRegistry daoRegistry, IJobCoordinator jobCoordinator, FhirContext fhirContext) {
        this.daoRegistry = daoRegistry;
        this.jobCoordinator = jobCoordinator;
        this.myFhirContext = fhirContext;
    }

    @Override
    public Class<InsurancePlan> getResourceType() { return InsurancePlan.class; }

    @Operation(name = "$export", idempotent = true, type = InsurancePlan.class, manualResponse = true)
    public void insurancePlanExport(ServletRequestDetails theRequestDetails) {
        BulkDataExportProvider bulkDataExportProvider = new BulkDataExportProvider();

        // require "Prefer: respond-async" request header
        BulkDataExportProvider.validatePreferAsyncHeader(theRequestDetails, "$export");

        BulkExportJobParameters bulkExportJobParameters = this.buildBulkExportJobParameters(null, DEFAULT_RESOURCE_TYPES);
        this.startJob(theRequestDetails, bulkExportJobParameters);
    }

    @Operation(name = "$export", idempotent = true, manualResponse = true)
    public void insurancePlanInstanceExport(@IdParam IIdType insurancePlanId, ServletRequestDetails theRequestDetails) {
        BulkDataExportProvider bulkDataExportProvider = new BulkDataExportProvider();

        // require "Prefer: respond-async" request header
        BulkDataExportProvider.validatePreferAsyncHeader(theRequestDetails, "$export");

        // validate if resource exists. Throws ResourceNotFoundException if doesn't exist
        InsurancePlan insurancePlan = daoRegistry.getResourceDao(InsurancePlan.class).read(new IdType(insurancePlanId.getIdPart()));


        String medicationIds = "";
        String locationIds = "";
        Set<String> resourceTypes = new HashSet<>();
        resourceTypes.add("InsurancePlan");

        // pulling referenced medication ids to pass to MedicationKnowledge type filters. can pull only reference id needed
        SearchParameterMap searchParameterMap = new SearchParameterMap();
        searchParameterMap.add("formulary", new ReferenceParam("InsurancePlan/"+insurancePlanId.getIdPart()));

        IBundleProvider basicBundle = daoRegistry.getResourceDao(Basic.class).search(searchParameterMap);

        if( insurancePlan.hasCoverageArea() ){
            locationIds = insurancePlan.getCoverageArea().stream().map(reference -> reference.getReference()).collect(Collectors.joining(","));
        }
        List<IPrimitiveType<String>> theTypeFilter = new ArrayList<>();
        theTypeFilter.add(new StringType("InsurancePlan?_id="+insurancePlanId.getIdPart()));
        if( basicBundle != null && basicBundle.size() > 0 ){
            resourceTypes.add("Basic");
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
            resourceTypes.add("MedicationKnowledge");
            theTypeFilter.add(new StringType("MedicationKnowledge?_id="+medicationIds));
        }
        if( locationIds != null && !locationIds.isEmpty() ){
            resourceTypes.add("Location");
            theTypeFilter.add(new StringType("Location?_id="+locationIds));
        }

        BulkExportJobParameters bulkExportJobParameters = this.buildBulkExportJobParameters(theTypeFilter, resourceTypes);
        this.startJob(theRequestDetails, bulkExportJobParameters);
    }

    @Operation(
            name = "$export-poll-status",
            manualResponse = true,
            idempotent = true,
            deleteEnabled = true
    )
    public void exportPollStatus(@OperationParam(name = "_jobId",typeName = "string",min = 0,max = 1) IPrimitiveType<String> theJobId, ServletRequestDetails theRequestDetails) throws IOException {
        HttpServletResponse response = theRequestDetails.getServletResponse();
        theRequestDetails.getServer().addHeadersToResponse(response);
        if (theJobId == null) {
            Parameters parameters = (Parameters)theRequestDetails.getResource();
            Parameters.ParametersParameterComponent parameter = (Parameters.ParametersParameterComponent)parameters.getParameter().stream().filter((param) -> param.getName().equals("_jobId")).findFirst().orElseThrow(() -> new InvalidRequestException(Msg.code(2227) + "$export-poll-status requires a job ID, please provide the value of target jobId."));
            theJobId = (IPrimitiveType)parameter.getValue();
        }

        JobInstance info = this.jobCoordinator.getInstance(theJobId.getValueAsString());

        switch (info.getStatus()) {
            case COMPLETED:
                if (theRequestDetails.getRequestType() == RequestTypeEnum.DELETE) {
                    this.handleDeleteRequest(theJobId, response, info.getStatus());
                } else {
                    response.setStatus(200);
                    response.setContentType("application/json");
                    BulkExportResponseJson bulkResponseDocument = new BulkExportResponseJson();
                    bulkResponseDocument.setTransactionTime(info.getEndTime());
                    bulkResponseDocument.setRequiresAccessToken(true);
                    String report = info.getReport();
                    if (StringUtils.isEmpty(report)) {
                        response.getWriter().close();
                    } else {
                        BulkExportJobResults results = (BulkExportJobResults) JsonUtil.deserialize(report, BulkExportJobResults.class);
                        bulkResponseDocument.setMsg(results.getReportMsg());
                        bulkResponseDocument.setRequest(results.getOriginalRequestUrl());
                        String serverBase = this.getServerBase(theRequestDetails);
                        bulkResponseDocument.getOutput();

                        for(Map.Entry<String, List<String>> entrySet : results.getResourceTypeToBinaryIds().entrySet()) {
                            String resourceType = (String)entrySet.getKey();
                            List<String> binaryIds = entrySet.getValue();
                            for(String binaryId : binaryIds) {
                                IIdType iId = new IdType(binaryId);
                                String nextUrl = serverBase + "/" + iId.toUnqualifiedVersionless().getValue();
                                bulkResponseDocument.addOutput().setType(resourceType).setUrl(nextUrl);
                            }
                        }
                        JsonUtil.serialize(bulkResponseDocument, response.getWriter());
                        response.getWriter().close();
                    }
                }
                break;
            case FAILED:
                response.setStatus(500);
                response.setContentType("application/json+fhir");
                IBaseOperationOutcome oo = OperationOutcomeUtil.newInstance(this.myFhirContext);
                OperationOutcomeUtil.addIssue(this.myFhirContext, oo, "error", info.getErrorMessage(), (String)null, (String)null);
                this.myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(oo, response.getWriter());
                response.getWriter().close();
                break;
            default:
                logger.warning("Unrecognized status encountered: {}. Treating as BUILDING/SUBMITTED"+ info.getStatus().name());
            case FINALIZE:
            case QUEUED:
            case IN_PROGRESS:
            case CANCELLED:
            case ERRORED:
                if (theRequestDetails.getRequestType() == RequestTypeEnum.DELETE) {
                    this.handleDeleteRequest(theJobId, response, info.getStatus());
                } else {
                    response.setStatus(202);
                    String dateString = this.getTransitionTimeOfJobInfo(info);
                    StatusEnum var10002 = info.getStatus();
                    response.addHeader("X-Progress", "Build in progress - Status set to " + var10002 + " at " + dateString);
                    response.addHeader("Retry-After", "120");
                }
        }
    }


    private BulkExportJobParameters buildBulkExportJobParameters(List<IPrimitiveType<String>> theTypeFilter, Set<String> resourceTypes) {
        BulkExportJobParameters bulkExportJobParameters = new BulkExportJobParameters();
        bulkExportJobParameters.setOutputFormat("application/fhir+ndjson");
        bulkExportJobParameters.setExportStyle(BulkExportJobParameters.ExportStyle.SYSTEM);
        bulkExportJobParameters.setResourceTypes(resourceTypes);
        if( theTypeFilter != null ){
            Set<String> typeFilters = this.splitTypeFilters(theTypeFilter);
            bulkExportJobParameters.setFilters(typeFilters);
        }
        return bulkExportJobParameters;
    }

    private void startJob(ServletRequestDetails theRequestDetails, BulkExportJobParameters theOptions) {
        JobInstanceStartRequest startRequest = new JobInstanceStartRequest();
        startRequest.setParameters(theOptions);
        startRequest.setJobDefinitionId("BULK_EXPORT");
        Batch2JobStartResponse response = this.jobCoordinator.startInstance(theRequestDetails, startRequest);
        this.writePollingLocationToResponseHeaders(theRequestDetails, response.getInstanceId());
    }

    private Set<String> splitTypeFilters(List<IPrimitiveType<String>> theTypeFilter) {
        if (theTypeFilter == null) {
            return null;
        }
        Set<String> retVal = new HashSet();

        for(IPrimitiveType<String> next : theTypeFilter) {
            String typeFilterString = next.getValueAsString();
            Arrays.stream(typeFilterString.split("(?:,)(?=[A-Z][a-z]+\\?)")).map(String::trim).filter(StringUtils::isNotBlank).forEach(retVal::add);
        }
        return retVal;
    }

    private void writePollingLocationToResponseHeaders(ServletRequestDetails theRequestDetails, String theInstanceId) {
        String serverBase = this.getServerBase(theRequestDetails);
        if (serverBase == null) {
            throw new InternalErrorException(Msg.code(2136) + "Unable to get the server base.");
        } else {
            String pollLocation = serverBase + "/InsurancePlan/$export-poll-status?_jobId=" + theInstanceId;
            pollLocation = UrlUtil.sanitizeHeaderValue(pollLocation);
            HttpServletResponse response = theRequestDetails.getServletResponse();
            theRequestDetails.getServer().addHeadersToResponse(response);
            response.addHeader("Content-Location", pollLocation);
            response.setStatus(202);
        }
    }

    private String getServerBase(ServletRequestDetails theRequestDetails) {
        return StringUtils.removeEnd(theRequestDetails.getServerBaseForRequest(), "/");
    }

    private String getTransitionTimeOfJobInfo(JobInstance theInfo) {
        if (theInfo.getEndTime() != null) {
            return (new InstantType(theInfo.getEndTime())).getValueAsString();
        } else {
            return theInfo.getStartTime() != null ? (new InstantType(theInfo.getStartTime())).getValueAsString() : "";
        }
    }

    private void handleDeleteRequest(IPrimitiveType<String> theJobId, HttpServletResponse response, StatusEnum theOrigStatus) throws IOException {
        IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(this.myFhirContext);
        JobOperationResultJson resultMessage = this.jobCoordinator.cancelInstance(theJobId.getValueAsString());
        if (theOrigStatus.equals(StatusEnum.COMPLETED)) {
            response.setStatus(404);
            OperationOutcomeUtil.addIssue(this.myFhirContext, outcome, "error", "Job instance <" + theJobId.getValueAsString() + "> was already cancelled or has completed.  Nothing to do.", (String)null, (String)null);
        } else {
            response.setStatus(202);
            OperationOutcomeUtil.addIssue(this.myFhirContext, outcome, "information", resultMessage.getMessage(), (String)null, "informational");
        }

        this.myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(outcome, response.getWriter());
        response.getWriter().close();
    }
}