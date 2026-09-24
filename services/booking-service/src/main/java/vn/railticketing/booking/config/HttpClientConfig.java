package vn.railticketing.booking.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import vn.railticketing.booking.client.InventoryClient;
import vn.railticketing.booking.client.QuotaClient;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public InventoryClient inventoryClient(
            RestClient.Builder builder,
            @Value("${clients.inventory.base-url}") String baseUrl,
            @Value("${clients.inventory.connect-timeout-ms:500}") int connectMs,
            @Value("${clients.inventory.read-timeout-ms:2000}") int readMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));

        RestClient restClient = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String cid = MDC.get("correlationId");
                    if (cid == null) cid = MDC.get("traceId");
                    if (cid != null) request.getHeaders().set("X-Correlation-Id", cid);
                    return execution.execute(request, body);
                })
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory proxy = HttpServiceProxyFactory.builderFor(adapter).build();
        return proxy.createClient(InventoryClient.class);
    }

    @Bean
    public QuotaClient quotaClient(
            RestClient.Builder builder,
            @Value("${clients.quota.base-url}") String baseUrl,
            @Value("${clients.quota.connect-timeout-ms:300}") int connectMs,
            @Value("${clients.quota.read-timeout-ms:800}") int readMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));

        RestClient restClient = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String cid = MDC.get("correlationId");
                    if (cid == null) cid = MDC.get("traceId");
                    if (cid != null) request.getHeaders().set("X-Correlation-Id", cid);
                    return execution.execute(request, body);
                })
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory proxy = HttpServiceProxyFactory.builderFor(adapter).build();
        return proxy.createClient(QuotaClient.class);
    }
}
