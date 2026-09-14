package vn.railticketing.schedule.config;

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

    // Initialized once; reads OTEL_* env vars (endpoint, service name, sampler).
    private final OpenTelemetrySdk sdk =
            AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();

    @Bean OpenTelemetrySdk  openTelemetrySdk()    { return sdk; }
    @Bean OpenTelemetry     openTelemetry()        { return sdk; }
    @Bean SdkTracerProvider sdkTracerProvider()   { return sdk.getSdkTracerProvider(); }
    @Bean ContextPropagators contextPropagators() { return sdk.getPropagators(); }

    // ── Micrometer Tracing ↔ OTel bridge ─────────────────────────────────────

    @Bean
    OtelCurrentTraceContext otelCurrentTraceContext() {
        return new OtelCurrentTraceContext();
    }

    // Puts traceId / spanId into MDC so log lines carry them.
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

    // Injects / extracts W3C trace headers in outbound HTTP calls.
    @Bean
    OtelPropagator micrometerOtelPropagator(OtelTracer tracer) {
        return new OtelPropagator(sdk.getPropagators(), tracer);
    }

    // Bridges WebMvcObservationAutoConfiguration observations → OTel spans.
    @Bean
    io.micrometer.observation.ObservationRegistryCustomizer<ObservationRegistry>
    tracingObservationHandler(OtelTracer tracer) {
        return registry -> registry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));
    }
}
