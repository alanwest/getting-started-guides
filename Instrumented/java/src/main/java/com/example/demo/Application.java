package com.example.demo;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.GlobalLoggerProvider;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.runtimemetrics.*;
import io.opentelemetry.instrumentation.spring.webmvc.v5_3.SpringWebMvcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.LogLimits;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.metrics.internal.view.ExponentialHistogramAggregation;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import javax.servlet.Filter;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@SpringBootApplication
public class Application {

    private static volatile OpenTelemetry openTelemetry = OpenTelemetry.noop();

    public static void main(String[] args) {
        // Configure OpenTelemetry as early as possible
        var openTelemetrySdk = openTelemetrySdk();
        Application.openTelemetry = openTelemetrySdk;

        // Set GlobalLoggerProvider, which is used by Log4j2 appender
        GlobalLoggerProvider.set(openTelemetrySdk.getSdkLoggerProvider());

        // Register runtime metrics instrumentation
        BufferPools.registerObservers(openTelemetrySdk);
        Classes.registerObservers(openTelemetrySdk);
        Cpu.registerObservers(openTelemetrySdk);
        GarbageCollector.registerObservers(openTelemetrySdk);
        MemoryPools.registerObservers(openTelemetrySdk);
        Threads.registerObservers(openTelemetrySdk);

        SpringApplication.run(Application.class, args);
    }

    @Bean
    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    // Add Spring WebMVC instrumentation by registering a tracing filter
    @Bean
    public Filter webMvcTracingFilter(OpenTelemetry openTelemetry) {
        return SpringWebMvcTelemetry.create(openTelemetry).createServletFilter();
    }

    private static OpenTelemetrySdk openTelemetrySdk() {
        // Environment variables for your OTLP exporters
        var newrelicLicenseKey = getEnvOrDefault("newrelicLicenseKey", Function.identity(), "");
        var newrelicOtlpEndpoint = getEnvOrDefault("newrelicOtlpEndpoint", Function.identity(), "https://otlp.nr-data.net:4317");

        // Configure resource
        var resource =
                Resource.getDefault().toBuilder()
                        .put(ResourceAttributes.SERVICE_NAME, "getting-started-java")
                        .put(ResourceAttributes.SERVICE_INSTANCE_ID, UUID.randomUUID().toString())
                        .build();
        // Configure tracer provider
        var sdkTracerProviderBuilder =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        // New Relic's max attribute length is 4095 characters
                        .setSpanLimits(
                                SpanLimits.getDefault().toBuilder().setMaxAttributeValueLength(4095).build());

        // Add otlp span exporter
        var spanExporterBuilder =
                OtlpGrpcSpanExporter.builder()
                        .setEndpoint(newrelicOtlpEndpoint)
                        .setCompression("gzip")
                        .addHeader("api-key", newrelicLicenseKey);
        sdkTracerProviderBuilder.addSpanProcessor(
                BatchSpanProcessor.builder(spanExporterBuilder.build()).build());

        // Configure meter provider
        var sdkMeterProviderBuilder = SdkMeterProvider.builder().setResource(resource);

        // Add otlp metric exporter
        var metricExporterBuilder =
                OtlpGrpcMetricExporter.builder()
                        .setEndpoint(newrelicOtlpEndpoint)
                        .setCompression("gzip")
                        .addHeader("api-key", newrelicLicenseKey)
                        // IMPORTANT: New Relic requires metrics to be delta temporality
                        .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
                        // Use exponential histogram aggregation for histogram instruments to produce better
                        // data
                        .setDefaultAggregationSelector(
                                DefaultAggregationSelector.getDefault()
                                        .with(InstrumentType.HISTOGRAM, ExponentialHistogramAggregation.getDefault()));
        // Register and build a metric reader
        sdkMeterProviderBuilder.registerMetricReader(
                PeriodicMetricReader.builder(metricExporterBuilder.build()).build());

        // Configure log emitter provider
        var sdkLogEmitterProvider =
                SdkLoggerProvider.builder()
                        // New Relic's max attribute length is 4095 characters
                        .setLogLimits(
                                () -> LogLimits.getDefault().toBuilder().setMaxAttributeValueLength(4095).build())
                        .setResource(resource);

        // Add otlp log exporter
        var logExporterBuilder =
                OtlpGrpcLogRecordExporter.builder()
                        .setEndpoint(newrelicOtlpEndpoint)
                        .setCompression("gzip")
                        .addHeader("api-key", newrelicLicenseKey);
        sdkLogEmitterProvider.addLogRecordProcessor(
                BatchLogRecordProcessor.builder(logExporterBuilder.build()).build());


        // Bring it all together
        return OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .setTracerProvider(sdkTracerProviderBuilder.build())
                .setMeterProvider(sdkMeterProviderBuilder.build())
                .setLoggerProvider(sdkLogEmitterProvider.build())
                .build();

    }

    private static <T> T getEnvOrDefault(
            String key, Function<String, T> transformer, T defaultValue) {
        return Optional.ofNullable(System.getenv(key))
                .filter(s -> !s.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty(key)))
                .filter(s -> !s.isBlank())
                .map(transformer)
                .orElse(defaultValue);
    }
}
