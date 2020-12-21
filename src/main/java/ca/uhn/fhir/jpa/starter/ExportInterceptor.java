package ca.uhn.fhir.jpa.starter;
import ca.uhn.fhir.rest.server.interceptor.*;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.jpa.starter.ExportData;
import java.util.*;
import java.io.*;

public class ExportInterceptor extends InterceptorAdapter {

   /**
    * Override the incomingRequestPreProcessed method, which is called
    * for each incoming request before any processing is done
    */
   @Override
   public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) {
     String endPoint = theRequest.getRequestURL().substring(theRequest.getRequestURL().lastIndexOf("/")+1);
     if (endPoint.equals("$export") && theRequest.getMethod().equals("GET")) {
         try {
            handleExport(theResponse);
         } catch (Exception e) {System.out.println("Exception: " + e.getMessage());}
         return false;
     }
      return true;
   }
   public void handleExport(HttpServletResponse theResponse) throws IOException {
       theResponse.setStatus(200);
       PrintWriter out = theResponse.getWriter();
       theResponse.setContentType("application/json");
       theResponse.setCharacterEncoding("UTF-8");
       String exportString = ExportData.loadExportData();
       out.print(exportString);
       out.flush();
   }

}
