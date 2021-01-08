package ca.uhn.fhir.jpa.starter;
import ca.uhn.fhir.rest.server.interceptor.*;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;

public class ExportInterceptor extends InterceptorAdapter {

   /**
    * Override the incomingRequestPreProcessed method, which is called
    * for each incoming request before any processing is done
    */
   @Override
   public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) {
     String endPoint = theRequest.getRequestURL().substring(theRequest.getRequestURL().lastIndexOf("/")+1);
     if (endPoint.equals("$export") && theRequest.getMethod().equals("GET")) {
         theResponse.setStatus(202);
         theResponse.setHeader("Content-Location", "https://davinci-drug-formulary-ri.logicahealth.org/resources/export.json");
         return false;
     }
      return true;
   }
}
