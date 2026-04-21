package org.example.recruitmentservice.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * DTO trả về thống kê CV cho một vị trí tuyển dụng.
 * Dùng bởi HR chatbot để trả lời các câu hỏi về số lượng CV mà không hallucinate.
 */
@Getter
@Builder
public class CvStatisticsResponse {
    private int positionId;
    private long total;
    private long scored;
    private long passed;
    private long failed;
}
