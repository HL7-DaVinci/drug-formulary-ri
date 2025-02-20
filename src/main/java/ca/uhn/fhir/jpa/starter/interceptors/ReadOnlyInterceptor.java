package ca.uhn.fhir.jpa.starter.interceptors;

import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;

public class ReadOnlyInterceptor extends InterceptorAdapter {
  @Override
  public void incomingRequestPreHandled(RestOperationTypeEnum theOperation, RequestDetails theProcessedRequest) {

    if (theOperation != RestOperationTypeEnum.EXTENDED_OPERATION_TYPE &&
        theOperation != RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE &&
        theOperation != RestOperationTypeEnum.EXTENDED_OPERATION_SERVER &&
        theOperation != RestOperationTypeEnum.HISTORY_INSTANCE &&
        theOperation != RestOperationTypeEnum.HISTORY_SYSTEM &&
        theOperation != RestOperationTypeEnum.HISTORY_TYPE &&
        theOperation != RestOperationTypeEnum.METADATA &&
        theOperation != RestOperationTypeEnum.READ &&
        theOperation != RestOperationTypeEnum.SEARCH_SYSTEM &&
        theOperation != RestOperationTypeEnum.SEARCH_TYPE &&
        theOperation != RestOperationTypeEnum.TRANSACTION &&
        theOperation != RestOperationTypeEnum.VALIDATE &&
        theOperation != RestOperationTypeEnum.VREAD &&
        theOperation != RestOperationTypeEnum.GET_PAGE) {
      throw new MethodNotAllowedException(theOperation.toString());
    }
    
  }
}
