package org.hl7.davinci;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.jobs.export.BulkDataExportProvider;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.annotations.OnEitherVersion;
import ca.uhn.fhir.rest.server.RestfulServer;
import org.hl7.davinci.interceptors.ExportInterceptor;
import org.hl7.davinci.interceptors.MetadataProvider;
import org.hl7.davinci.interceptors.PatientAuthorizationInterceptor;
import org.hl7.davinci.interceptors.ReadOnlyInterceptor;
import org.hl7.davinci.resourceproviders.InsurancePlanExportProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers all Da Vinci Drug Formulary customizations on top of the
 * unmodified HAPI FHIR JPA starter: FHIR server interceptors, the
 * InsurancePlan bulk export provider, and the OAuth, SMART well-known,
 * and debug servlet endpoints.
 */
@Configuration
public class FormularyConfig {

  @Autowired
  public void registerFormularyCustomizations(RestfulServer restfulServer, AppProperties appProperties,
      FormularyProperties formularyProperties, DaoRegistry daoRegistry, IJobCoordinator jobCoordinator,
      FhirContext fhirContext, BulkDataExportProvider bulkDataExportProvider) {
    restfulServer.registerInterceptor(new ExportInterceptor());
    restfulServer.registerInterceptor(new PatientAuthorizationInterceptor(formularyProperties));
    restfulServer.registerInterceptor(new ReadOnlyInterceptor());
    restfulServer.registerInterceptor(new MetadataProvider(appProperties));
    restfulServer.registerProvider(
        new InsurancePlanExportProvider(daoRegistry, jobCoordinator, fhirContext, bulkDataExportProvider));
  }

  @Bean
  @Conditional(OnEitherVersion.class)
  public ServletRegistrationBean<DispatcherServlet> wellKnownServletRegistration() {
    ServletRegistrationBean<DispatcherServlet> registration = new ServletRegistrationBean<>(new DispatcherServlet(), "/fhir/.well-known/*");
    registration.addInitParameter("contextConfigLocation", "org.hl7.davinci.wellknown.WellKnownEndpointController");
    registration.addInitParameter("contextClass", "org.springframework.web.context.support.AnnotationConfigWebApplicationContext");
    registration.setLoadOnStartup(4);
    return registration;
  }

  @Bean
  @Conditional(OnEitherVersion.class)
  public ServletRegistrationBean<DispatcherServlet> oauthServletRegistration() {
    ServletRegistrationBean<DispatcherServlet> registration = new ServletRegistrationBean<>(new DispatcherServlet(), "/fhir/oauth/*");
    registration.addInitParameter("contextConfigLocation", "org.hl7.davinci.authorization.OauthEndpointController");
    registration.addInitParameter("contextClass", "org.springframework.web.context.support.AnnotationConfigWebApplicationContext");
    registration.setLoadOnStartup(3);
    return registration;
  }

  @Bean
  @Conditional(OnEitherVersion.class)
  public ServletRegistrationBean<DispatcherServlet> debugServletRegistration() {
    ServletRegistrationBean<DispatcherServlet> registration = new ServletRegistrationBean<>(new DispatcherServlet(), "/fhir/debug/*");
    registration.addInitParameter("contextConfigLocation", "org.hl7.davinci.debug.DebugEndpointController");
    registration.addInitParameter("contextClass", "org.springframework.web.context.support.AnnotationConfigWebApplicationContext");
    registration.setLoadOnStartup(2);
    return registration;
  }
}
