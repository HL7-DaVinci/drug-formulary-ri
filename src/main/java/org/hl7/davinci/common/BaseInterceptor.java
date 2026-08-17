package org.hl7.davinci.common;

import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;

/**
 * Base class for FHIR interceptors to handle autowiring the RestfulServer and registering the interceptor.
 * 
 * Example usage:
 * <pre>
 * {@code @Component }
 * public class MyInterceptor extends BaseInterceptor {
 *   {@code @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)}
 *   public void update(IBaseResource theOldResource, IBaseResource theResource) {
 *     // Custom logic here
 *   }
 * }
 * </pre>
 */
public abstract class BaseInterceptor {

  @Autowired
  protected RestfulServer restfulServer;

  @PostConstruct
  public void registerInterceptor() {
    restfulServer.registerInterceptor(this);
  }
  
}