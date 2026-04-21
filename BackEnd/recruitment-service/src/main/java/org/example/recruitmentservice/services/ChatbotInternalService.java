package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.recruitmentservice.dto.response.ActivePositionResponse;
import org.example.recruitmentservice.dto.response.ApplicationSummaryResponse;
import org.example.recruitmentservice.dto.response.CvStatisticsResponse;
import org.example.recruitmentservice.dto.response.PositionDetailsResponse;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CVAnalysisRepository;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service xử lý các yêu cầu lấy dữ liệu nội bộ (internal) phục vụ cho Chatbot.
 * Tách biệt logic truy xuất dữ liệu và mapping khỏi Controller để tuân thủ SRP.
 */
@Service
@RequiredArgsConstructor
public class ChatbotInternalService {

    private final PositionRepository positionRepository;
    private final CandidateCVRepository candidateCVRepository;
    private final CVAnalysisRepository cvAnalysisRepository;

    /**
     * Returns full JD text for a set of position IDs.
     * Used by chatbot-service for Small-to-Big retrieval:
     * Qdrant returns chunk hits → extract unique positionIds → call this method → pass full JD to Gemini Pro.
     *
     * @param positionIds list of position IDs to retrieve (duplicates are de-duplicated)
     */
    public List<PositionDetailsResponse> getPositionDetails(List<Integer> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return List.of();
        }
        Set<Integer> uniqueIds = Set.copyOf(positionIds);
        return positionRepository.findAllById(uniqueIds)
                .stream()
                .map(this::toPositionDetailsResponse)
                .collect(Collectors.toList());
    }

    /**
     * Lấy danh sách các vị trí đang mở (active).
     * Dùng cho Chatbot để lọc phạm vi tìm kiếm JD trên Qdrant.
     */
    public List<ActivePositionResponse> getActivePositions() {
        return positionRepository.findAllActive()
                .stream()
                .map(this::toActivePositionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Lấy danh sách tóm tắt các ứng viên đã nộp đơn cho một vị trí cụ thể.
     * HR Chatbot dùng candidateId list này để lọc các Master CV vectors trên Qdrant.
     */
    public List<ApplicationSummaryResponse> getApplicationsByPosition(int positionId) {
        return candidateCVRepository
                .findApplicationsByPositionId(positionId)
                .stream()
                .map(this::toApplicationSummaryResponse)
                .collect(Collectors.toList());
    }

    /**
     * Thống kê CV cho HR chatbot: tổng số, đã chấm, pass (>=75), fail.
     * Dùng bởi get_cv_summary tool để trả lời chính xác khi HR hỏi số lượng CV.
     *
     * @param positionId ID vị trí cần thống kê
     * @param passThreshold ngưỡng điểm pass (mặc định 75)
     */
    public CvStatisticsResponse getCvStatistics(int positionId, int passThreshold) {
        long total  = candidateCVRepository.countByPositionId(positionId);  // int → auto-widened to long
        long scored = cvAnalysisRepository.countScoredByPositionId(positionId);
        long passed = cvAnalysisRepository.countPassedByPositionId(positionId, passThreshold);
        return CvStatisticsResponse.builder()
                .positionId(positionId)
                .total(total)
                .scored(scored)
                .passed(passed)
                .failed(scored - passed)
                .build();
    }

    private PositionDetailsResponse toPositionDetailsResponse(Positions position) {
        return PositionDetailsResponse.builder()
                .id(position.getId())
                .name(position.getName())
                .language(position.getLanguage())
                .level(position.getLevel())
                .jdText(position.getJobDescription())
                .build();
    }

    private ActivePositionResponse toActivePositionResponse(Positions position) {
        String openedAt = position.getOpenedAt() != null
                ? position.getOpenedAt().toString()
                : null;
        return ActivePositionResponse.builder()
                .id(position.getId())
                .name(position.getName())
                .language(position.getLanguage())
                .level(position.getLevel())
                .openedAt(openedAt)
                .build();
    }

    private ApplicationSummaryResponse toApplicationSummaryResponse(CandidateCV cv) {
        ApplicationSummaryResponse.ApplicationSummaryResponseBuilder builder = ApplicationSummaryResponse.builder()
                .candidateId(cv.getCandidateId())
                .candidateName(cv.getName())
                .candidateEmail(cv.getEmail())
                .appCvId(cv.getId());

        // Lấy score/feedback từ CVAnalysis (sử dụng repository để tránh LazyInitializationException)
        cvAnalysisRepository.findByCandidateCV_Id(cv.getId()).ifPresent(analysis -> 
            builder.score(analysis.getScore())
                   .feedback(analysis.getFeedback())
                   .skillMatch(analysis.getSkillMatch())
                   .skillMiss(analysis.getSkillMiss())
        );

        return builder.build();
    }
}
