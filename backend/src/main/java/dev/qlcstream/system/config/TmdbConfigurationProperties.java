package dev.qlcstream.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qlc-stream.tmdb")
public record TmdbConfigurationProperties(String baseUrl, String apiToken) {
    public boolean configured() { return apiToken != null && !apiToken.isBlank(); }
}
