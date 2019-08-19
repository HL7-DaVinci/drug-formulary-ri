package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirVersionEnum;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

public class ApplicationContext extends AnnotationConfigWebApplicationContext {

    public ApplicationContext() {
        register(FhirServerConfigR4.class, FhirServerConfigCommon.class);

        if (HapiProperties.getSubscriptionWebsocketEnabled()) {
            register(ca.uhn.fhir.jpa.config.WebsocketDispatcherConfig.class);
        }

    }

}
