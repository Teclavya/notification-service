package com.teclavya.notification.service;

import com.teclavya.notification.entities.NotificationPreference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Timezone-aware quiet-hours evaluator over {@link NotificationPreference#getQuietHoursStart()}/
 * {@link NotificationPreference#getQuietHoursEnd()}/{@link NotificationPreference#isQuietHoursEnabled()}
 * + {@link NotificationPreference#getTimezone()}, per {@code specs/learning-journey/design.md §6}.
 *
 * <h3>Timezone precedence</h3>
 * An explicit {@code queueTimezone} (from enqueue metadata, e.g.
 * {@code LifecycleMessageReviewQueue.timezone}) wins; otherwise the preference row's own
 * {@code timezone}; otherwise UTC.
 *
 * <h3>Critical distinction (design §6)</h3>
 * A {@link NotificationPreference} row is a JPA entity with {@code @Builder.Default} values
 * (22:00–07:00, enabled=true) that apply whenever a NEW transient instance is constructed
 * (e.g. via {@code NotificationPreference.builder().build()}). This evaluator must NEVER be
 * handed such a transient/default-filled object as if it were a persisted row — an ABSENT
 * preference (no row found for the student+notification type) must resolve to
 * <b>NOT QUIET</b> (never silently block a student who never configured preferences). The
 * method signature below takes an {@code Optional<NotificationPreference>} so "not found" is
 * represented unambiguously by {@code Optional.empty()} — callers must pass the repository's
 * own {@code Optional} result directly and must NOT unwrap it into a default-filled transient
 * object before calling this evaluator.
 */
@Service
@Slf4j
public class QuietHoursEvaluator {

    /** Entity default when a persisted row exists but leaves start/end/enabled null (shouldn't
     * happen given {@code @Builder.Default}, but guarded defensively for hand-built/JPA-loaded
     * rows with explicit nulls). */
    private static final int DEFAULT_START_HOUR = 22;
    private static final int DEFAULT_END_HOUR = 7;

    /**
     * Evaluate whether "now" falls within the student's configured quiet-hours window.
     *
     * @param preference    the repository lookup result for this student+notification type —
     *                       {@code Optional.empty()} means no persisted row exists and this
     *                       method returns {@code false} (not quiet) unconditionally.
     * @param queueTimezone the timezone captured at enqueue time (e.g. from
     *                      {@code metadata.timezone}); may be {@code null}/blank, in which case
     *                      the preference row's timezone is used, falling back to UTC.
     * @param now           the instant to evaluate against (caller-supplied so tests are
     *                      deterministic; production callers pass {@code Instant.now()}).
     * @return {@code true} iff {@code now}, converted to the resolved local timezone, falls
     *         within the student's quiet-hours window and quiet hours are enabled.
     */
    public boolean isQuiet(Optional<NotificationPreference> preference, String queueTimezone, Instant now) {
        if (preference == null || preference.isEmpty()) {
            // No persisted row ⇒ never silently block a student who never configured this.
            return false;
        }
        NotificationPreference pref = preference.get();
        if (!pref.isQuietHoursEnabled()) {
            return false;
        }

        ZoneId zoneId = resolveZone(queueTimezone, pref.getTimezone());
        int startHour = pref.getQuietHoursStart() != null ? pref.getQuietHoursStart() : DEFAULT_START_HOUR;
        int endHour = pref.getQuietHoursEnd() != null ? pref.getQuietHoursEnd() : DEFAULT_END_HOUR;

        int localHour = ZonedDateTime.ofInstant(now, zoneId).getHour();
        return isWithinWindow(localHour, startHour, endHour);
    }

    /**
     * Window logic, hour-granularity, wraps midnight.
     * <ul>
     *   <li>{@code start == end}: treated as "always quiet" (24h window) — degenerate but explicit.</li>
     *   <li>{@code start < end}: quiet iff {@code start <= hour < end} (same-day window).</li>
     *   <li>{@code start > end}: wraps midnight — quiet iff {@code hour >= start || hour < end}.</li>
     * </ul>
     */
    private boolean isWithinWindow(int localHour, int startHour, int endHour) {
        if (startHour == endHour) {
            return true;
        }
        if (startHour < endHour) {
            return localHour >= startHour && localHour < endHour;
        }
        return localHour >= startHour || localHour < endHour;
    }

    private ZoneId resolveZone(String queueTimezone, String preferenceTimezone) {
        ZoneId resolved = tryParseZone(queueTimezone);
        if (resolved != null) {
            return resolved;
        }
        resolved = tryParseZone(preferenceTimezone);
        if (resolved != null) {
            return resolved;
        }
        return ZoneId.of("UTC");
    }

    private ZoneId tryParseZone(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(candidate.trim());
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' supplied to QuietHoursEvaluator; falling back", candidate);
            return null;
        }
    }
}
