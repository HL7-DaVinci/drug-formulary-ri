package org.hl7.davinci.common;

import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.annotation.PostConstruct;

/**
 * Base class for FHIR providers to handle autowiring the RestfulServer and registering the provider.
 * 
 * Example usage:
 * <pre>
 * {@code @Component }
 * public class MyProvider extends BaseProvider {
 *   {@code @Operation(name = "$my-operation") }
 *   public OperationOutcome myOperation() {
 *     return new OperationOutcome();
 *   }
 * }
 * </pre>
 */
public abstract class BaseProvider {

  @Autowired
  protected RestfulServer restfulServer;

  @PostConstruct
  public void registerProvider() {
    restfulServer.registerProvider(this);
  }
  
}
