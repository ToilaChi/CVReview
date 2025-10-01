package org.example.recruitmentservice.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.springframework.web.multipart.MultipartFile;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PositionsRequest {
    private String name;
    private String language;
    private String level;
    private MultipartFile file;
}

