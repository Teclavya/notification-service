package com.teclavya.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Feature flags for the notification-service side of the learning-journey ethical
 * send-gate (Increment 1(b), design.md §10, ADR-8).
 *
 * <p>Read from {@code feature.flags.lifecycle.*} in application.yml, env-overridable
 * per the platform's existing {@code feature.flags.*} convention — mirrors LPT's
 * {@code LifecycleFeatureFlags} bean.
 */
@Configuration
@ConfigurationProperties(prefix = "feature.flags.lifecycle")
@Getter
@Setter
public class NsLifecycleFeatureFlags {

    /**
     * Nested {@code send-gate.enabled} switch: master control for
     * {@link com.teclavya.notification.scheduler.LifecycleSendGatePoller}.
     *
     * <p>Default <b>false</b> (ship dark) — OFF means the endpoint still accepts and
     * enqueues {@code /send-gated} requests (rows sit at DRAFTED), but the poller never
     * verifies/approves/sends (AC-10.2). Independent of LPT's {@code journey.enabled} — a
     * canary can enable LPT emission while this stays OFF to prove ingress without any
     * student delivery.
     *
     * <p>Env override: {@code FF_LIFECYCLE_SEND_GATE_ENABLED=true}.
     */
    private SendGate sendGate = new SendGate();

    @Getter
    @Setter
    public static class SendGate {
        private boolean enabled = false;
    }
}
