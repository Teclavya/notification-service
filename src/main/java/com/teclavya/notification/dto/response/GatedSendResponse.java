package com.teclavya.notification.dto.response;

import lombok.*;

/**
 * Response body for POST /api/v1/notifications/internal/send-gated.
 *
 * Returned with HTTP 202 Accepted — the row is enqueued into the lifecycle
 * send-gate (status=DRAFTED) but not yet delivered. Delivery is driven
 * asynchronously by the LifecycleSendGatePoller (NS-BE-4a/4b).
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GatedSendResponse {

    /** lifecycle_message_review_queue.id */
    private String queueId;

    /** Always "DRAFTED" at enqueue time. */
    private String status;
}
