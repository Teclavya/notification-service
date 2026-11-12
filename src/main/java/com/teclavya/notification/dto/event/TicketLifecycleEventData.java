package com.teclavya.notification.dto.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TicketLifecycleEventData {
    private String ticketNumber;
    private String ticketId;

    @JsonProperty("title")
    @JsonAlias({"subject"})
    private String title;

    @JsonProperty("subject")
    public String getSubject() {
        return title;
    }

    private String description;
    private String priority;
    private String category;
    private String status;

    @JsonProperty("studentId")
    @JsonAlias({"userId", "user_id", "student_id"})
    private String studentId;

    @JsonProperty("userId")
    public String getUserId() {
        return studentId;
    }

    @JsonProperty("studentEmail")
    @JsonAlias({"userEmail", "user_email", "student_email"})
    private String studentEmail;

    @JsonProperty("userEmail")
    public String getUserEmail() {
        return studentEmail;
    }

    private String assignedAgentId;

    @JsonProperty("escalated")
    @JsonAlias({"isEscalated", "is_escalated"})
    private boolean escalated;

    public boolean isEscalated() {
        return escalated;
    }

    public void setEscalated(boolean escalated) {
        this.escalated = escalated;
    }

    @JsonProperty("isEscalated")
    public boolean getIsEscalated() {
        return escalated;
    }

    private Instant escalatedAt;
    private String escalationReason;
    private Instant firstResponseDeadline;
    private Instant resolutionDeadline;
    private Instant firstResponseAt;
    private Instant resolvedAt;
    private Long countdownRemainingSeconds;
    private Instant occurredAt;
}
