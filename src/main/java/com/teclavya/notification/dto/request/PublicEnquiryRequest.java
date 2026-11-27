package com.teclavya.notification.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicEnquiryRequest {

    public enum EnquiryTopic {
        SUPPORT("support", "Learner & Technical Support", "support@teclavya.com", "Within 24 hours"),
        INFO("info", "General & Partnerships", "info@teclavya.com", "Within 1 business day"),
        CAREERS("careers", "Careers & Hiring", "careers@teclavya.com", "Within 3 business days"),
        PRIVACY("privacy", "Privacy & DPO (PDPA)", "privacy@teclavya.com", "Within 3 business days");

        private final String code;
        private final String label;
        private final String targetAlias;
        private final String sla;

        EnquiryTopic(String code, String label, String targetAlias, String sla) {
            this.code = code;
            this.label = label;
            this.targetAlias = targetAlias;
            this.sla = sla;
        }

        public String getCode() { return code; }
        public String getLabel() { return label; }
        public String getTargetAlias() { return targetAlias; }
        public String getSla() { return sla; }

        @JsonCreator
        public static EnquiryTopic fromValue(String raw) {
            if (raw == null) return null;
            for (EnquiryTopic t : values()) {
                if (t.code.equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
            throw new IllegalArgumentException("Unsupported enquiry topic: " + raw);
        }
    }

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
    private String fullName;

    @NotBlank(message = "Email address is required")
    @Email(message = "Please enter a valid email address")
    @Size(max = 254, message = "Email address is too long")
    private String email;

    @NotNull(message = "Enquiry topic is required")
    private EnquiryTopic topic;

    @NotBlank(message = "Message is required")
    @Size(min = 10, max = 2000, message = "Message must be between 10 and 2000 characters")
    private String message;

    /** Honeypot field — must remain blank for human submissions. */
    private String website;
}
