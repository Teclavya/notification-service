package com.teclavya.notification.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutonomyMetricsResponse {

    private String currentTier;
    private long consecutiveCleanDays;
    private long daysToGraduation;
    private long totalEvaluated;
    private long totalVetoed;
    private boolean graduated;
}
