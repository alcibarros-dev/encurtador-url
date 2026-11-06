package br.com.encurtador.bff.config;

import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

/** Clientes HTTP do BFF com os timeouts curtos de {@code bff.connect-timeout} e {@code bff.read-timeout} (D5). */
public final class HttpClients {

    private HttpClients() {
    }

    public static JdkClientHttpRequestFactory requestFactory(BffProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
