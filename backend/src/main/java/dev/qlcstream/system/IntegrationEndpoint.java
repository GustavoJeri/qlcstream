package dev.qlcstream.system;

import java.net.URI;

/** Appends an API path without discarding the configured reverse-proxy subpath. */
public final class IntegrationEndpoint {
    private IntegrationEndpoint() { }

    public static URI api(String baseUrl, String path) {
        var base = URI.create(baseUrl == null ? "" : baseUrl);
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !path.startsWith("/")) throw new IllegalArgumentException();
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }
}
