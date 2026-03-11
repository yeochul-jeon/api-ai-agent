package com.apiagent.tracing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 트레이싱 설정.
 * OTEL_EXPORTER_OTLP_ENDPOINT 환경변수 설정 시 활성화.
 */
@Configuration
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true", matchIfMissing = false)
public class TracingConfig {
    // Micrometer + OTLP 자동 설정은 Spring Boot Actuator가 처리.
    // 추가 커스텀 설정이 필요한 경우 여기에 Bean 추가.
}
