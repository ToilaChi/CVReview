package org.example.recruitmentservice.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class PositionsResponse {
    private int id;
    private String name;
    private String language;
    private String level;
    private String jdPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
