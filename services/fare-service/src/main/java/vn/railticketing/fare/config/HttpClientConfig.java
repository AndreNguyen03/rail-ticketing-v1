package vn.railticketing.fare.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import vn.railticketing.fare.client.ScheduleClient;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public ScheduleClient scheduleClient(
            RestClient.Builder builder,
            @Value("${clients.schedule.base-url}") String baseUrl,
            @Value("${clients.schedule.connect-timeout-ms:500}") int connectMs,
            @Value("${clients.schedule.read-timeout-ms:2000}") int readMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));

        RestClient restClient = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String cid = MDC.get("correlationId");
                    if (cid != null) request.getHeaders().set("X-Correlation-Id", cid);
                    return execution.execute(request, body);
                })
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build().createClient(ScheduleClient.class);
    }
}
