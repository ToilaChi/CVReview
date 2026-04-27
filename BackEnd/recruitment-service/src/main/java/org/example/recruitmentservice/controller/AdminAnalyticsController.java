package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.recruitmentservice.dto.response.CvTrafficResponse;
import org.example.recruitmentservice.dto.response.ProcessingTimeResponse;
import org.example.recruitmentservice.models.enums.CVStatus;
import org.example.recruitmentservice.repository.CandidateCVRepository;
import org.example.recruitmentservice.repository.ProcessingBatchRepository;
import org.example.recruitmentservice.scheduler.GarbageCollectionJob;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final CandidateCVRepository candidateCVRepository;
    private final ProcessingBatchRepository processingBatchRepository;
    private final GarbageCollectionJob garbageCollectionJob;

    @GetMapping("/cv-traffic")
    public ApiResponse<CvTrafficResponse> getCvTraffic(
            @org.springframework.web.bind.annotation.RequestParam(value = "days", defaultValue = "30") int days) {
        try {
            LocalDateTime dateSince = LocalDateTime.now().minusDays(days);

            long totalCv = candidateCVRepository.countTotalCVsAfterDate(dateSince);
            long successCv = candidateCVRepository.countByCvStatusAndDateAfter(CVStatus.EMBEDDED, dateSince);
            long failedCv = candidateCVRepository.countByCvStatusAndDateAfter(CVStatus.FAILED, dateSince);

            long processingCv = totalCv - successCv - failedCv;
            if (processingCv < 0)
                processingCv = 0; // fallback in case of race condition

            CvTrafficResponse response = CvTrafficResponse.builder()
                    .totalCv(totalCv)
                    .successCv(successCv)
                    .failedCv(failedCv)
                    .processingCv(processingCv)
                    .days(days)
                    .build();

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "CV traffic retrieved successfully", response);
        } catch (Exception e) {
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), "Failed to get CV traffic", null);
        }
    }

    @GetMapping("/processing-time")
    public ApiResponse<ProcessingTimeResponse> getAverageProcessingTime(
            @org.springframework.web.bind.annotation.RequestParam(value = "days", defaultValue = "30") int days) {
        try {
            LocalDateTime dateSince = LocalDateTime.now().minusDays(days);

            Double t1 = processingBatchRepository.getAverageTimeFor1To10CVs(dateSince);
            Double t2 = processingBatchRepository.getAverageTimeFor11To20CVs(dateSince);
            Double t3 = processingBatchRepository.getAverageTimeFor21To30CVs(dateSince);
            Double t4 = processingBatchRepository.getAverageTimeForMoreThan30CVs(dateSince);

            java.util.List<ProcessingTimeResponse.BucketTime> buckets = new java.util.ArrayList<>();
            if (t1 != null)
                buckets.add(new ProcessingTimeResponse.BucketTime("1-10 CVs", t1));
            if (t2 != null)
                buckets.add(new ProcessingTimeResponse.BucketTime("11-20 CVs", t2));
            if (t3 != null)
                buckets.add(new ProcessingTimeResponse.BucketTime("21-30 CVs", t3));
            if (t4 != null)
                buckets.add(new ProcessingTimeResponse.BucketTime("> 30 CVs", t4));

            ProcessingTimeResponse response = ProcessingTimeResponse.builder()
                    .days(days)
                    .buckets(buckets)
                    .build();

            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Processing time retrieved successfully", response);
        } catch (Exception e) {
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), "Failed to get processing time", null);
        }
    }

    @PostMapping("/trigger-gc")
    public ApiResponse<String> triggerGarbageCollection() {
        try {
            garbageCollectionJob.purgeFailedCVFiles();
            return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), "Garbage collection triggered manually", "Success");
        } catch (Exception e) {
            e.printStackTrace();
            return new ApiResponse<>(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), "Failed to trigger GC", null);
        }
    }
}
