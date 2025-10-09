package org.example.recruitmentservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.example.recruitmentservice.models.Positions;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public class PositionsResponse {
    private int id;
    private String name;
    private String language;
    private String level;
    private String jdPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PositionsResponse(Positions positions) {
        this.id = positions.getId();
        this.name = positions.getName();
        this.language = positions.getLanguage();
        this.level = positions.getLevel();
        this.jdPath = positions.getJdPath();
        this.createdAt = positions.getCreatedAt();
        this.updatedAt = positions.getUpdatedAt();
    }

    public PositionsResponse(int id, String name, String language, String level, String jdPath) {
        this.id = id;
        this.name = name;
        this.language = language;
        this.level = level;
        this.jdPath = jdPath;
    }
}
