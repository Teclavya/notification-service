package com.teclavya.notification.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicEnquiryResponse {
    private String referenceId;
    private String routedTo;
    private String sla;
}
