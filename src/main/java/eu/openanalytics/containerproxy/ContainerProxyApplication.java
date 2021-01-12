/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2020 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy;

import com.fasterxml.jackson.datatype.jsr353.JSR353Module;
import eu.openanalytics.containerproxy.util.ProxyMappingManager;
import io.undertow.Handlers;
import io.undertow.servlet.api.ServletSessionConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.redis.RedisHealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.web.filter.FormContentFilter;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Properties;

@SpringBootApplication
@ComponentScan("eu.openanalytics")
public class ContainerProxyApplication {
	public static final String CONFIG_FILENAME = "application.yml";
	public static final String CONFIG_DEMO_PROFILE = "demo";

	@Inject
	private Environment environment;

	@Inject
	private ProxyMappingManager mappingManager;

	private final Logger log = LogManager.getLogger(getClass());

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(ContainerProxyApplication.class);

		boolean hasExternalConfig = Files.exists(Paths.get(CONFIG_FILENAME));
		if (!hasExternalConfig) app.setAdditionalProfiles(CONFIG_DEMO_PROFILE);

		setDefaultProperties(app);

		try {
			app.setLogStartupInfo(false);
			app.run(args);
		} catch (Exception e) {
			// Workaround for bug in UndertowEmbeddedServletContainer.start():
			// If undertow.start() fails, started remains false which prevents undertow.stop() from ever being called.
			// Undertow's (non-daemon) XNIO worker threads will then prevent the JVM from exiting.
			if (e instanceof PortInUseException) System.exit(-1);
		}
	}

	@PostConstruct
	public void init() {
		if (environment.getProperty("server.use-forward-headers") != null) {
			log.warn("WARNING: Using server.use-forward-headers will not work in this ShinyProxy release. See https://shinyproxy.io/documentation/security/#https-ssl--tls on how to change your configuration.");
		}
	}

	@Bean
	public UndertowServletWebServerFactory servletContainer() {
		UndertowServletWebServerFactory factory = new UndertowServletWebServerFactory();
		factory.addDeploymentInfoCustomizers(info -> {
			info.setPreservePathOnForward(false); // required for the /api/route/{id}/ endpoint to work properly
			if (Boolean.valueOf(environment.getProperty("logging.requestdump", "false"))) {
				info.addOuterHandlerChainWrapper(defaultHandler -> Handlers.requestDump(defaultHandler));
			}
			info.addInnerHandlerChainWrapper(defaultHandler -> {
				return mappingManager.createHttpHandler(defaultHandler);
			});
			ServletSessionConfig sessionConfig = new ServletSessionConfig();
			sessionConfig.setHttpOnly(true);
			sessionConfig.setSecure(Boolean.valueOf(environment.getProperty("server.secureCookies", "false")));
			info.setServletSessionConfig(sessionConfig);
		});
		try {
			factory.setAddress(InetAddress.getByName(environment.getProperty("proxy.bind-address", "0.0.0.0")));
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Invalid bind address specified", e);
		}
		factory.setPort(Integer.parseInt(environment.getProperty("proxy.port", "8080")));
		return factory;
	}

	// Disable specific Spring filters that parse the request body, preventing it from being proxied.

	@Bean
	public FilterRegistrationBean<FormContentFilter> registration2(FormContentFilter filter) {
		FilterRegistrationBean<FormContentFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	/**
	 * Register the Jackson module which implements compatibility between javax.json and Jackson.
	 *
	 * @return
	 */
	@Bean
	public JSR353Module jsr353Module() {
		return new JSR353Module();
	}

	/**
	 * Compatibility with AWS ElastiCache
	 *
	 * @return
	 */
	@Bean
	public static ConfigureRedisAction configureRedisAction() {
		return ConfigureRedisAction.NO_OP;
	}

	@Bean
	public HealthIndicator redisSessionHealthIndicator(RedisConnectionFactory rdeRedisConnectionFactory) {
		if (Objects.equals(environment.getProperty("spring.session.store-type"), "redis")) {
			// if we are using redis for session -> use a proper health check for redis
			return new RedisHealthIndicator(rdeRedisConnectionFactory);
		} else {
			// not using redis for session -> just pretend it's always online
			return new HealthIndicator() {

				@Override
				public Health getHealth(boolean includeDetails) {
					return Health.up().build();
				}

				@Override
				public Health health() {
					return Health.up().build();
				}
			};
		}
	}

	private static void setDefaultProperties(SpringApplication app) {
		Properties properties = new Properties();

		// use in-memory session storage by default. Can be overwritten in application.yml
		properties.put("spring.session.store-type", "none");

		// disable multi-part handling by Spring. We don't need this anywhere in the application.
		// When enabled this will cause problems when proxying file-uploads to the shiny apps.
		properties.put("spring.servlet.multipart.enabled", "false");

		// disable logging of requests, since this reads part of the requests and therefore undertow is unable to correctly handle those requests
		properties.put("logging.level.org.springframework.web.servlet.DispatcherServlet", "INFO");

		properties.put("spring.application.name", "ContainerProxy");

		// Health configuration
		// ====================

		// enable redisSession check for the readiness probe
		properties.put("management.endpoint.health.group.readiness.include", "readinessProbe,redisSession");
		// disable ldap health endpoint
		properties.put("management.health.ldap.enabled", false);
		// disable default redis health endpoint since it's managed by redisSession
		properties.put("management.health.redis.enabled", "false");
		// enable Kubernetes porobes
		properties.put("management.endpoint.health.probes.enabled", true);

		// ====================

		app.setDefaultProperties(properties);
	}

}