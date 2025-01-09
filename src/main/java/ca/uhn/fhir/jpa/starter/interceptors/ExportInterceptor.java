package ca.uhn.fhir.jpa.starter.interceptors;

import ca.uhn.fhir.jpa.starter.ServerLogger;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.interceptor.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.logging.Logger;

import jakarta.servlet.http.HttpServletRequest;

public class ExportInterceptor extends InterceptorAdapter {
  private static final Logger logger = ServerLogger.getLogger();
  /*private AppProperties appProperties;

  public ExportInterceptor(AppProperties appProperties) {
    this.appProperties = appProperties;
  }*/

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
      //String serverBaseAddress = appProperties.getServer_address().replace("fhir", "");
      if (path.equals("/fhir/InsurancePlan/$export")) {
        //theResponse.setStatus(202);
        //theResponse.setHeader("Content-Location", serverBaseAddress + "resources/export.json");
        return true;
      } else if (path.startsWith("/fhir/InsurancePlan/")) {
        //String id = path.replace("/fhir/InsurancePlan/", "").replace("/$export", "");
        //theResponse.setStatus(202);
        //theResponse.setHeader("Content-Location", serverBaseAddress + "resources/" + id + "/export.json");
        return true;
      } else {
        String message = "Server only allows $export on InsurancePlan Endpoint (All InsurancePlan or Specific InsurancePlan).";
        throw new InvalidRequestException(message);
      }
    }

    return true;
  }
}
