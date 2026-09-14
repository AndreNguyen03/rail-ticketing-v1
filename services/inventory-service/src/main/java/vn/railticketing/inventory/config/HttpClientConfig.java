package vn.railticketing.inventory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import vn.railticketing.inventory.client.ScheduleClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public ScheduleClient scheduleClient(
            RestClient.Builder builder,
            @Value("${clients.schedule.base-url}") String baseUrl) {

        RestClient restClient = builder.baseUrl(baseUrl).build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(ScheduleClient.class);
    }
}
