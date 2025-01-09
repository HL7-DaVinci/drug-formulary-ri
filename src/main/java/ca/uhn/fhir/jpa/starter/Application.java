package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.batch2.api.IJobCoordinator;
import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.starter.annotations.OnEitherVersion;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.fhir.jpa.starter.cr.StarterCrDstu3Config;
import ca.uhn.fhir.jpa.starter.cr.StarterCrR4Config;
import ca.uhn.fhir.jpa.starter.interceptors.ExportInterceptor;
import ca.uhn.fhir.jpa.starter.interceptors.MetadataProvider;
import ca.uhn.fhir.jpa.starter.interceptors.PatientAuthorizationInterceptor;
import ca.uhn.fhir.jpa.starter.interceptors.ReadOnlyInterceptor;
import ca.uhn.fhir.jpa.starter.mdm.MdmConfig;
import ca.uhn.fhir.jpa.starter.resourceproviders.InsurancePlanExportProvider;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.subscription.match.config.SubscriptionProcessorConfig;
import ca.uhn.fhir.jpa.subscription.match.config.WebsocketDispatcherConfig;
import ca.uhn.fhir.jpa.subscription.submit.config.SubscriptionSubmitterConfig;
import ca.uhn.fhir.rest.server.RestfulServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;

@ServletComponentScan(basePackageClasses = {RestfulServer.class})
@SpringBootApplication(exclude = {ElasticsearchRestClientAutoConfiguration.class, ThymeleafAutoConfiguration.class})
@Import({
	StarterCrR4Config.class,
	StarterCrDstu3Config.class,
	StarterCdsHooksConfig.class,
	SubscriptionSubmitterConfig.class,
	SubscriptionProcessorConfig.class,
	SubscriptionChannelConfig.class,
	WebsocketDispatcherConfig.class,
	MdmConfig.class,
	JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public class Application extends SpringBootServletInitializer {

	public static void main(String[] args) {

		SpringApplication.run(Application.class, args);

		// Server is now accessible at eg. http://localhost:8080/fhir/metadata
		// UI is now accessible at http://localhost:8080/
	}

	@Autowired
	AppProperties appProperties;

	@Autowired
	AutowireCapableBeanFactory beanFactory;

	@Autowired
	DaoRegistry daoRegistry;

	@Autowired
	IJobCoordinator jobCoordinator;

	@Autowired
	FhirContext myFhirContext;

	@Bean
	@Conditional(OnEitherVersion.class)
	public ServletRegistrationBean hapiServletRegistration(RestfulServer restfulServer) {

		restfulServer.registerInterceptor(new ExportInterceptor());
		restfulServer.registerInterceptor(new PatientAuthorizationInterceptor(appProperties));
		restfulServer.registerInterceptor(new ReadOnlyInterceptor());
		restfulServer.registerInterceptor(new MetadataProvider(appProperties));
		restfulServer.registerProvider(new InsurancePlanExportProvider(daoRegistry, jobCoordinator, myFhirContext));

		ServletRegistrationBean servletRegistrationBean = new ServletRegistrationBean();
		beanFactory.autowireBean(restfulServer);
		servletRegistrationBean.setServlet(restfulServer);
		servletRegistrationBean.addUrlMappings("/fhir/*");
		servletRegistrationBean.setLoadOnStartup(1);

		return servletRegistrationBean;
	}

	@Bean
	@Conditional(OnEitherVersion.class)
	public ServletRegistrationBean wellKnownServletRegistration() {
		ServletRegistrationBean registration = new ServletRegistrationBean<>(new DispatcherServlet(), "/fhir/.well-known/*");
		registration.addInitParameter("contextConfigLocation", "ca.uhn.fhir.jpa.starter.wellknown.WellKnownEndpointController");
		registration.addInitParameter("contextClass", "org.springframework.web.context.support.AnnotationConfigWebApplicationContext");
		registration.setLoadOnStartup(4); // Optional: Set load-on-startup order
		return registration;
	}

	@Bean
	@Conditional(OnEitherVersion.class)
	public ServletRegistrationBean oauthServletRegistration() {
		ServletRegistrationBean registration = new ServletRegistrationBean<>(new DispatcherServlet(), "/fhir/oauth/*");
		registration.addInitParameter("contextConfigLocation", "ca.uhn.fhir.jpa.starter.authorization.OauthEndpointController");
		registration.addInitParameter("contextClass", "org.springframework.web.context.support.AnnotationConfigWebApplicationContext");
		registration.setLoadOnStartup(3); // Optional: Set load-on-startup order
		return registration;
	}

	@Bean
	@Conditional(OnEitherVersion.class)
	public ServletRegistrationBean debugServletRegistration() {
		ServletRegistrationBean registration = new ServletRegistrationBean<>(new DispatcherServlet(), "/fhir/debug/*");
		registration.addInitParameter("contextConfigLocation", "ca.uhn.fhir.jpa.starter.debug.DebugEndpointController");
		registration.addInitParameter("contextClass", "org.springframework.web.context.support.AnnotationConfigWebApplicationContext");
		registration.setLoadOnStartup(2); // Optional: Set load-on-startup order
		return registration;
	}
}
