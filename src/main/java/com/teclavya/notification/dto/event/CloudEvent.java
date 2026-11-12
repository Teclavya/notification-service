package com.teclavya.notification.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudEvent<T> {
    private String id;
    private String source;
    private String specversion;
    private String type;
    private Instant time;
    private String datacontenttype;
    private T data;
}
