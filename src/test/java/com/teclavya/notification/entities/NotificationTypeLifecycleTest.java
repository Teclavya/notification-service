package com.teclavya.notification.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * BE-1: verifies the four lifecycle send-gate values are present in NotificationType.
 */
class NotificationTypeLifecycleTest {

    @Test
    @DisplayName("INACTIVITY_REENGAGEMENT is resolvable by name")
    void inactivityReengagement() {
        assertSame(NotificationType.INACTIVITY_REENGAGEMENT,
                NotificationType.valueOf("INACTIVITY_REENGAGEMENT"));
    }

    @Test
    @DisplayName("STRUGGLE_INTERVENTION is resolvable by name")
    void struggleIntervention() {
        assertSame(NotificationType.STRUGGLE_INTERVENTION,
                NotificationType.valueOf("STRUGGLE_INTERVENTION"));
    }

    @Test
    @DisplayName("MASTERY_CELEBRATION is resolvable by name")
    void masteryCelebration() {
        assertSame(NotificationType.MASTERY_CELEBRATION,
                NotificationType.valueOf("MASTERY_CELEBRATION"));
    }

    @Test
    @DisplayName("ONBOARDING_NUDGE is resolvable by name")
    void onboardingNudge() {
        assertSame(NotificationType.ONBOARDING_NUDGE,
                NotificationType.valueOf("ONBOARDING_NUDGE"));
    }
}
