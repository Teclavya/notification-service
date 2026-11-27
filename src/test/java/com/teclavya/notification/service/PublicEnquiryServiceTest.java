package com.teclavya.notification.service;

import com.teclavya.notification.dto.request.PublicEnquiryRequest;
import com.teclavya.notification.dto.request.PublicEnquiryRequest.EnquiryTopic;
import com.teclavya.notification.dto.response.PublicEnquiryResponse;
import com.teclavya.notification.entities.PublicEnquiry;
import com.teclavya.notification.repo.PublicEnquiryRepository;
import com.teclavya.notification.service.impl.EmailTemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicEnquiryServiceTest {

    @Mock
    private PublicEnquiryRepository repository;
    @Mock
    private EmailService emailService;

    private PublicEnquiryService service;

    @BeforeEach
    void setUp() {
        service = new PublicEnquiryService(repository, emailService, new EmailTemplateRenderer());
    }

    @Test
    void submit_validInfoEnquiry_routesToInfoAliasWithReplyTo() {
        when(repository.save(any(PublicEnquiry.class))).thenAnswer(inv -> inv.getArgument(0));
        PublicEnquiryRequest req = PublicEnquiryRequest.builder()
                .fullName("Aarav Mehta")
                .email("aarav@example.com")
                .topic(EnquiryTopic.INFO)
                .message("Interested in cohort licensing for 120 students.")
                .website("")
                .build();

        PublicEnquiryResponse res = service.submit(req, "203.0.113.10");

        assertThat(res.getReferenceId()).startsWith("ENQ-");
        assertThat(res.getRoutedTo()).isEqualTo("info@teclavya.com");
        assertThat(res.getSla()).isEqualTo("Within 1 business day");

        verify(emailService).sendEmail(
                eq("info@teclavya.com"),
                anyString(),
                anyString(),
                eq("aarav@example.com")
        );
        verify(repository, times(2)).save(any(PublicEnquiry.class));
    }

    @Test
    void submit_honeypotPopulated_dropsSilentlyWithoutEmailOrDb() {
        PublicEnquiryRequest req = PublicEnquiryRequest.builder()
                .fullName("Spam Bot")
                .email("bot@spam.example")
                .topic(EnquiryTopic.SUPPORT)
                .message("Buy cheap links now!!")
                .website("https://spam.example")
                .build();

        PublicEnquiryResponse res = service.submit(req, "198.51.100.5");

        assertThat(res.getReferenceId()).isEqualTo("ENQ-RECEIVED");
        verify(repository, never()).save(any());
        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void submit_escapesHtmlAndStripsCrLf() {
        when(repository.save(any(PublicEnquiry.class))).thenAnswer(inv -> inv.getArgument(0));
        PublicEnquiryRequest req = PublicEnquiryRequest.builder()
                .fullName("Alice\r\nBcc:evil@attacker.com")
                .email("alice@example.com")
                .topic(EnquiryTopic.PRIVACY)
                .message("Please export my data <script>alert(1)</script>.")
                .build();

        service.submit(req, "192.0.2.1");

        ArgumentCaptor<String> subjectCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("privacy@teclavya.com"), subjectCap.capture(), bodyCap.capture(), eq("alice@example.com"));

        assertThat(subjectCap.getValue()).doesNotContain("\r").doesNotContain("\n");
        assertThat(bodyCap.getValue()).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(bodyCap.getValue()).doesNotContain("<script>");
    }

    @Test
    void submit_mailFailure_marksFailedAndRethrows() {
        when(repository.save(any(PublicEnquiry.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new MailSendException("SMTP down"))
                .when(emailService).sendEmail(anyString(), anyString(), anyString(), anyString());

        PublicEnquiryRequest req = PublicEnquiryRequest.builder()
                .fullName("Bob")
                .email("bob@example.com")
                .topic(EnquiryTopic.CAREERS)
                .message("Applying for Senior Backend Engineer role.")
                .build();

        assertThatThrownBy(() -> service.submit(req, "192.0.2.2"))
                .isInstanceOf(MailSendException.class);

        ArgumentCaptor<PublicEnquiry> saved = ArgumentCaptor.forClass(PublicEnquiry.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
    }
}
