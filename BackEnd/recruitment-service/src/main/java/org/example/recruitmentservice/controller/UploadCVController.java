package org.example.recruitmentservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.recruitmentservice.services.UploadCVService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadCVController {
    private final UploadCVService uploadCVService;

    @PreAuthorize("hasRole('HR')")
    @PostMapping("/hr/cv")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadCVsByHR(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("positionId") Integer positionId,
            HttpServletRequest request) {
        return ResponseEntity.ok(
            uploadCVService.uploadCVsByHR(files, positionId, request)
        );
    }

    @PreAuthorize("hasRole('CANDIDATE')")
    @PostMapping("/candidate/cv")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadCVByCandidate(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        return ResponseEntity.ok(
            uploadCVService.uploadCVByCandidate(file, request)
        );
    }
}
