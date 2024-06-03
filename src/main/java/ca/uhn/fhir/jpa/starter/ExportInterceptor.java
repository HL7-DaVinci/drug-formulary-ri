package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.interceptor.*;
import javax.servlet.http.HttpServletResponse;

import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

public class ExportInterceptor extends InterceptorAdapter {
  private static final Logger logger = ServerLogger.getLogger();

  /**
   * Override the incomingRequestPreProcessed method, which is called
   * for each incoming request before any processing is done
   */
  @Override
  public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) {
    String method = theRequest.getMethod();
    String path = theRequest.getServletPath() + theRequest.getPathInfo();
    
    if (method.equals("GET") && path.endsWith("/$export")) {
      logger.info("EXPORT INCOMING REQUEST TO: " + path);
      String serverBaseAddress = HapiProperties.getServerAddress().replace("fhir/", "");

      if (path.equals("/fhir/InsurancePlan/$export")) {
        theResponse.setStatus(202);
        theResponse.setHeader("Content-Location", serverBaseAddress + "resources/export.json");
        return false;
      } else if (path.startsWith("/fhir/InsurancePlan/")) {
        String id = path.replace("/fhir/InsurancePlan/", "").replace("/$export", "");
        theResponse.setStatus(202);
        theResponse.setHeader("Content-Location", serverBaseAddress + "resources/" + id + "/export.json");
        return false;
      } else {
        String message = "Server only allows $export on InsurancePlan Endpoint (All InsurancePlan or Specific InsurancePlan).";
        throw new InvalidRequestException(message);
      }
    }

    return true;
  }
}
