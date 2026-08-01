package com.teclavya.notification.service;

import com.teclavya.notification.entities.NotificationPreference;
import com.teclavya.notification.entities.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuietHoursEvaluator} — design.md §6 / roadmap NS-BE-3.
 *
 * <p>Pure unit tests, no Spring context needed. All instants are constructed explicitly per
 * timezone so tests are deterministic and independent of the machine's local clock.
 */
class QuietHoursEvaluatorTest {

    private final QuietHoursEvaluator evaluator = new QuietHoursEvaluator();

    private static Instant utcInstantAtHour(int hour) {
        return ZonedDateTime.of(2026, 7, 31, hour, 0, 0, 0, ZoneId.of("UTC")).toInstant();
    }

    private static Instant utcInstantAt(int hour, int minute) {
        return ZonedDateTime.of(2026, 7, 31, hour, minute, 0, 0, ZoneId.of("UTC")).toInstant();
    }

    private static NotificationPreference prefWithWindow(int start, int end, boolean enabled, String tz) {
        return NotificationPreference.builder()
                .studentId("student-1")
                .notificationType(NotificationType.GENERAL)
                .quietHoursStart(start)
                .quietHoursEnd(end)
                .quietHoursEnabled(enabled)
                .timezone(tz)
                .build();
    }

    // -----------------------------------------------------------------------
    // CRITICAL: persisted-vs-transient distinction (design §6)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("No persisted preference row (Optional.empty()) => NOT quiet, regardless of hour, " +
            "even at an hour a default-filled transient object (22:00-07:00 enabled=true) would call quiet")
    void noRowFound_neverQuiet_evenAtDefaultQuietHour() {
        // 23:00 UTC would be "quiet" under the entity's own @Builder.Default (22-07, enabled=true) —
        // proving this test would fail if the evaluator were ever handed a default-filled transient
        // instead of Optional.empty().
        boolean quiet = evaluator.isQuiet(Optional.empty(), null, utcInstantAtHour(23));
        assertThat(quiet).isFalse();
    }

    @Test
    @DisplayName("Null Optional passed defensively => NOT quiet (never NPE, never quiet)")
    void nullOptional_neverQuiet() {
        boolean quiet = evaluator.isQuiet(null, null, utcInstantAtHour(23));
        assertThat(quiet).isFalse();
    }

    // -----------------------------------------------------------------------
    // quietHoursEnabled=false => never quiet
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("quietHoursEnabled=false => never quiet, even squarely inside the configured window")
    void disabled_neverQuiet() {
        NotificationPreference pref = prefWithWindow(22, 7, false, "UTC");
        boolean quiet = evaluator.isQuiet(Optional.of(pref), null, utcInstantAtHour(23));
        assertThat(quiet).isFalse();
    }

    // -----------------------------------------------------------------------
    // In-window / out-of-window (UTC, wrap-midnight window start=22 end=7)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("In-window: 23:00 UTC, window 22-07 => quiet")
    void inWindow_midnightWrap() {
        NotificationPreference pref = prefWithWindow(22, 7, true, "UTC");
        boolean quiet = evaluator.isQuiet(Optional.of(pref), null, utcInstantAtHour(23));
        assertThat(quiet).isTrue();
    }

    @Test
    @DisplayName("Out-of-window: 12:00 UTC, window 22-07 => not quiet")
    void outOfWindow_midday() {
        NotificationPreference pref = prefWithWindow(22, 7, true, "UTC");
        boolean quiet = evaluator.isQuiet(Optional.of(pref), null, utcInstantAtHour(12));
        assertThat(quiet).isFalse();
    }

    // -----------------------------------------------------------------------
    // Wrap-midnight boundaries: 23:59 / 00:00 / 06:59 / 07:00
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Wrap-midnight boundary hours (window 22:00-07:00)")
    class WrapMidnightBoundaries {

        private final NotificationPreference pref = prefWithWindow(22, 7, true, "UTC");

        @Test
        @DisplayName("23:59 => quiet (inside window, just before midnight)")
        void justBeforeMidnight_quiet() {
            assertThat(evaluator.isQuiet(Optional.of(pref), null, utcInstantAt(23, 59))).isTrue();
        }

        @Test
        @DisplayName("00:00 => quiet (inside window, just after midnight)")
        void justAfterMidnight_quiet() {
            assertThat(evaluator.isQuiet(Optional.of(pref), null, utcInstantAt(0, 0))).isTrue();
        }

        @Test
        @DisplayName("06:59 => quiet (last minute before window end)")
        void justBeforeWindowEnd_quiet() {
            assertThat(evaluator.isQuiet(Optional.of(pref), null, utcInstantAt(6, 59))).isTrue();
        }

        @Test
        @DisplayName("07:00 => NOT quiet (window end is exclusive)")
        void atWindowEnd_notQuiet() {
            assertThat(evaluator.isQuiet(Optional.of(pref), null, utcInstantAt(7, 0))).isFalse();
        }

        @Test
        @DisplayName("22:00 => quiet (window start is inclusive)")
        void atWindowStart_quiet() {
            assertThat(evaluator.isQuiet(Optional.of(pref), null, utcInstantAt(22, 0))).isTrue();
        }

        @Test
        @DisplayName("21:59 => NOT quiet (just before window start)")
        void justBeforeWindowStart_notQuiet() {
            assertThat(evaluator.isQuiet(Optional.of(pref), null, utcInstantAt(21, 59))).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Timezone precedence: queue.timezone (metadata) > preference.timezone > UTC
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Non-UTC student tz conversion: preference tz=Asia/Kolkata (UTC+5:30), " +
            "instant=17:00 UTC == 22:30 IST => quiet under a 22-07 window")
    void nonUtcPreferenceTimezone_conversion() {
        NotificationPreference pref = prefWithWindow(22, 7, true, "Asia/Kolkata");
        Instant instant = ZonedDateTime.of(2026, 7, 31, 17, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        boolean quiet = evaluator.isQuiet(Optional.of(pref), null, instant);
        assertThat(quiet).isTrue();
    }

    @Test
    @DisplayName("Explicit queue.timezone overrides preference.timezone (precedence)")
    void queueTimezoneTakesPrecedenceOverPreferenceTimezone() {
        // Preference says America/New_York (would be daytime at this instant), but the enqueue
        // metadata's queue.timezone (Asia/Kolkata, 22:30 local) must win => quiet.
        NotificationPreference pref = prefWithWindow(22, 7, true, "America/New_York");
        Instant instant = ZonedDateTime.of(2026, 7, 31, 17, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        boolean quiet = evaluator.isQuiet(Optional.of(pref), "Asia/Kolkata", instant);
        assertThat(quiet).isTrue();
    }

    @Test
    @DisplayName("Blank/invalid queue.timezone falls back to preference.timezone, then UTC")
    void invalidQueueTimezone_fallsBackToPreferenceTimezone() {
        NotificationPreference pref = prefWithWindow(22, 7, true, "Asia/Kolkata");
        Instant instant = ZonedDateTime.of(2026, 7, 31, 17, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        boolean quiet = evaluator.isQuiet(Optional.of(pref), "not-a-real-zone", instant);
        assertThat(quiet).isTrue();
    }

    @Test
    @DisplayName("No timezone anywhere (queue null, preference null) => defaults to UTC")
    void noTimezoneAnywhere_defaultsToUtc() {
        NotificationPreference pref = prefWithWindow(22, 7, true, null);
        boolean quiet = evaluator.isQuiet(Optional.of(pref), null, utcInstantAtHour(23));
        assertThat(quiet).isTrue();
    }
}
