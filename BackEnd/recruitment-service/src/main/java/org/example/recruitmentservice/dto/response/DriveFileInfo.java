package org.example.recruitmentservice.dto.response;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DriveFileInfo {
    private String fileId;
    private String fileName;
    private String webViewLink;
    private String webContentLink;
}
