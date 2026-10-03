package com.seatreservation.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Platforms like Render and Heroku hand out DATABASE_URL in the form
 * postgres://user:pass@host:port/name, which is not a JDBC URL. This converts
 * it before the datasource is configured. JDBC URLs (jdbc:h2:..., jdbc:postgresql:...)
 * and an explicit SPRING_DATASOURCE_URL pass through untouched.
 *
 * Registered from main() rather than META-INF/spring/...imports because the Boot
 * repackager moves META-INF off the application classpath in the fat jar.
 */
public class PostgresUrlInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();
        String url = env.getProperty("DATABASE_URL");
        if (url == null || !(url.startsWith("postgres://") || url.startsWith("postgresql://"))) return;
        if (env.getProperty("SPRING_DATASOURCE_URL") != null) return;

        URI uri = URI.create(url);
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) jdbc.append(':').append(uri.getPort());
        jdbc.append(uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath());
        if (uri.getQuery() != null) jdbc.append('?').append(uri.getQuery());

        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", jdbc.toString());
        if (uri.getUserInfo() != null) {
            String[] up = uri.getUserInfo().split(":", 2);
            props.put("spring.datasource.username", URLDecoder.decode(up[0], StandardCharsets.UTF_8));
            if (up.length > 1) props.put("spring.datasource.password", URLDecoder.decode(up[1], StandardCharsets.UTF_8));
        }
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrlConversion", props));
    }
}
