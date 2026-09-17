package vn.railticketing.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import vn.railticketing.gateway.client.WaitingRoomClient;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public WaitingRoomClient waitingRoomClient(
            RestClient.Builder builder,
            @Value("${clients.waiting-room.base-url}") String baseUrl,
            @Value("${clients.waiting-room.connect-timeout-ms:300}") int connectMs,
            @Value("${clients.waiting-room.read-timeout-ms:300}") int readMs) {
        // Timeout NGẮN cố ý: filter này chạy trên đường HOT PATH của mọi
        // request vào contention plane. waiting-room chậm/chết KHÔNG được
        // kéo cả gateway theo — QueueAdmissionFilter fail-open khi timeout.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));

        RestClient restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory proxy = HttpServiceProxyFactory.builderFor(adapter).build();
        return proxy.createClient(WaitingRoomClient.class);
    }
}
