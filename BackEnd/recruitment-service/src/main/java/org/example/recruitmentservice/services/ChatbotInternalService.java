package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import org.example.recruitmentservice.dto.response.ActivePositionResponse;
import org.example.recruitmentservice.dto.response.ApplicationSummaryResponse;
import org.example.recruitmentservice.dto.response.CandidateApplicationStatusResponse;
import org.example.recruitmentservice.dto.response.CvStatisticsResponse;
import org.example.recruitmentservice.dto.response.PositionDetailsResponse;
import org.example.recruitmentservice.models.entity.CandidateCV;
import org.example.recruitmentservice.models.entity.Positions;
import org.example.recruitmentservice.repository.CVAnalysisRepository;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.PositionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
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
         * Qdrant returns chunk hits → extract unique positionIds → call this method →
         * pass full JD to Gemini Pro.
         *
         * @param positionIds list of position IDs to retrieve (duplicates are
         *                    de-duplicated)
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
         * HR Chatbot dùng candidateId list này để lọc các Master CV vectors trên
         * Qdrant.
         */
        public List<ApplicationSummaryResponse> getApplicationsByPosition(int positionId) {
                List<CandidateCV> cvs = candidateCVRepository.findApplicationsByPositionId(positionId);
                if (cvs.isEmpty()) return List.of();
                
                List<Integer> cvIds = cvs.stream().map(CandidateCV::getId).collect(Collectors.toList());
                java.util.Map<Integer, org.example.recruitmentservice.models.entity.CVAnalysis> analysisMap = 
                        cvAnalysisRepository.findByCandidateCV_IdIn(cvIds).stream()
                        .collect(Collectors.toMap(a -> a.getCandidateCV().getId(), a -> a));
                        
                return cvs.stream()
                                .map(cv -> toApplicationSummaryResponse(cv, analysisMap.get(cv.getId())))
                                .collect(Collectors.toList());
        }

        /**
         * Thống kê CV cho HR chatbot: tổng số, đã chấm, pass (>=75), fail.
         * Dùng bởi get_cv_summary tool để trả lời chính xác khi HR hỏi số lượng CV.
         *
         * @param positionId    ID vị trí cần thống kê
         * @param passThreshold ngưỡng điểm pass (mặc định 75)
         */
        public CvStatisticsResponse getCvStatistics(int positionId, int passThreshold, String mode) {
                org.example.recruitmentservice.models.enums.SourceType sourceType = "HR_MODE".equals(mode) ? org.example.recruitmentservice.models.enums.SourceType.HR : org.example.recruitmentservice.models.enums.SourceType.CANDIDATE;
                long total = candidateCVRepository.countByPositionIdAndSourceType(positionId, sourceType);
                long scored = cvAnalysisRepository.countScoredByPositionIdAndSourceType(positionId, sourceType);
                long passed = cvAnalysisRepository.countPassedByPositionIdAndSourceType(positionId, sourceType, passThreshold);
                return CvStatisticsResponse.builder()
                                .positionId(positionId)
                                .total(total)
                                .scored(scored)
                                .passed(passed)
                                .failed(scored - passed)
                                .build();
        }

        /**
         * Trả về trạng thái ứng tuyển của một candidate — dùng bởi
         * check_application_status tool.
         * Cho phép Candidate chatbot trả lời "Tôi đã apply chưa?" mà không cần điều
         * hướng UI.
         *
         * @param candidateId UUID của ứng viên
         * @param positionId  Optional — nếu có, chỉ trả về application của position đó
         */
        public CandidateApplicationStatusResponse getApplicationStatus(
                        String candidateId, Optional<Integer> positionId) {

                List<CandidateCV> applications = positionId
                                .map(pid -> candidateCVRepository
                                                .findApplicationsByCandidateIdAndPositionId(candidateId, pid))
                                .orElseGet(() -> candidateCVRepository.findApplicationsByCandidateId(candidateId));

                List<CandidateApplicationStatusResponse.ApplicationRecord> records = applications.stream()
                                .map(cv -> {
                                        Integer score = cvAnalysisRepository.findByCandidateCV_Id(cv.getId())
                                                        .map(a -> a.getScore())
                                                        .orElse(null);
                                        String posName = cv.getPosition() != null ? cv.getPosition().getName() : null;
                                        String status = score != null ? (score >= 70 ? "Pass" : "Reviewing")
                                                        : "Pending scoring";
                                        return CandidateApplicationStatusResponse.ApplicationRecord.builder()
                                                        .positionId(cv.getPosition() != null ? cv.getPosition().getId()
                                                                        : null)
                                                        .positionName(posName)
                                                        .score(score)
                                                        .status(status)
                                                        .build();
                                })
                                .collect(Collectors.toList());

                return CandidateApplicationStatusResponse.builder()
                                .candidateId(candidateId)
                                .applications(records)
                                .build();
        }

        // -------------------------------------------------------
        // Mapping helpers (private)
        // -------------------------------------------------------

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

        private ApplicationSummaryResponse toApplicationSummaryResponse(CandidateCV cv, org.example.recruitmentservice.models.entity.CVAnalysis analysis) {
                ApplicationSummaryResponse.ApplicationSummaryResponseBuilder builder = ApplicationSummaryResponse
                                .builder()
                                .candidateId(cv.getCandidateId())
                                .candidateName(cv.getName())
                                .candidateEmail(cv.getEmail())
                                .appCvId(cv.getId())
                                .sourceType(cv.getSourceType() != null ? cv.getSourceType().name() : null);

                if (analysis != null) {
                        builder.score(analysis.getScore())
                               .feedback(analysis.getFeedback())
                               .skillMatch(analysis.getSkillMatch())
                               .skillMiss(analysis.getSkillMiss());
                }

                return builder.build();
        }
}
