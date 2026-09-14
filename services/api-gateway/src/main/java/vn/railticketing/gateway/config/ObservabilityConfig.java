package vn.railticketing.gateway.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ObservabilityConfig {

    private final OpenTelemetrySdk sdk =
            AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();

    @Bean OpenTelemetrySdk  openTelemetrySdk()    { return sdk; }
    @Bean OpenTelemetry     openTelemetry()        { return sdk; }
    @Bean SdkTracerProvider sdkTracerProvider()   { return sdk.getSdkTracerProvider(); }
    @Bean ContextPropagators contextPropagators() { return sdk.getPropagators(); }

    @Bean
    OtelCurrentTraceContext otelCurrentTraceContext() {
        return new OtelCurrentTraceContext();
    }

    @Bean
    Slf4JEventListener slf4JEventListener() {
        return new Slf4JEventListener();
    }

    @Bean
    OtelTracer micrometerOtelTracer(ApplicationContext ctx,
                                    OtelCurrentTraceContext traceCtx) {
        return new OtelTracer(
                sdk.getTracer("io.micrometer.tracing"),
                traceCtx,
                ctx::publishEvent,
                new OtelBaggageManager(sdk, List.of(), List.of()));
    }

    @Bean
    OtelPropagator micrometerOtelPropagator(OtelTracer tracer) {
        return new OtelPropagator(sdk.getPropagators(), tracer);
    }

    @Bean
    io.micrometer.observation.ObservationRegistryCustomizer<ObservationRegistry>
    tracingObservationHandler(OtelTracer tracer) {
        return registry -> registry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));
    }
}
