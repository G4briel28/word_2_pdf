package pe.saeta.word2pdf.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Un único {@link HttpClient} compartido: mantiene un pool de conexiones keep-alive hacia ONLYOFFICE
 * y evita abrir una conexión TCP por cada conversión.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean(destroyMethod = "close")
    public HttpClient onlyOfficeHttpClient(OnlyOfficeProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
