package ca.uhn.fhir.jpa.starter.datainitializer;

import java.nio.charset.StandardCharsets;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.util.FileCopyUtils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import jakarta.annotation.PostConstruct;

@Configuration
@Conditional(NonEmptyInitialDataCondition.class)
public class DataInitializer {

  private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

  @Autowired
  private FhirContext fhirContext;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private DataInitializerProperties dataInitializerProperties;

  @Autowired
  private ResourceLoader resourceLoader;

  @PostConstruct
  public void initializeData() {

    if (dataInitializerProperties.getInitialData() == null || dataInitializerProperties.getInitialData().isEmpty()) {
      return;
    }

    logger.info("Initializing data");

  for (String directoryPath : dataInitializerProperties.getInitialData()) {
      logger.info("Loading resources from directory: " + directoryPath);

      Resource[] resources = null;

      try {
        resources = ResourcePatternUtils.getResourcePatternResolver(resourceLoader).getResources("classpath:" + directoryPath + "/**/*.json");  
      } catch (Exception e) {
        logger.error("Error loading resources from directory: " + directoryPath, e);
        continue;
      }

      // Retry loop to allow out-of-order loading while keeping referential integrity enabled.
      // If a resource fails due to missing references, we defer it to a later pass.
      java.util.List<Resource> queue = new java.util.ArrayList<>(java.util.Arrays.asList(resources));
      int pass = 0;
      int loadedTotal = 0;

      while (!queue.isEmpty()) {
        pass++;
        int loadedThisPass = 0;

        java.util.Iterator<Resource> it = queue.iterator();
        while (it.hasNext()) {
          Resource resource = it.next();
          try {
            String resourceText = new String(FileCopyUtils.copyToByteArray(resource.getInputStream()), StandardCharsets.UTF_8);
            IBaseResource fhirResource = fhirContext.newJsonParser().parseResource(resourceText);
            IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(fhirResource);
            dao.update(fhirResource, new SystemRequestDetails());
            it.remove();
            loadedThisPass++;
            loadedTotal++;
            logger.debug("Loaded resource: {} (pass {})", resource.getFilename(), pass);
          } catch (Exception e) {
            // Defer and try again in the next pass
            logger.trace("Deferring resource {} until dependencies exist: {}", resource.getFilename(), e.getMessage());
          }
        }

        logger.info("Pass {} complete. Loaded {} resources ({} remaining).", pass, loadedThisPass, queue.size());

        if (loadedThisPass == 0) {
          // No progress made; break to avoid infinite loop and report failures.
          for (Resource remaining : queue) {
            logger.warn("Failed to load resource after {} passes: {}", pass, remaining.getFilename());
          }
          break;
        }
      }
      logger.info("Finished loading directory {}. Loaded {} resources.", directoryPath, loadedTotal);

    }

  }
}