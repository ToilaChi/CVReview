package org.example.recruitmentservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JDParsedEvent {
    private Integer positionId;
    private String hrId;
    private String position;
    private String jdText;
}