package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.ApiResponse;
import org.example.recruitmentservice.dto.response.CandidateCVResponse;
import org.example.recruitmentservice.services.UploadCVService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/cv")
@RequiredArgsConstructor
public class UploadCVController {
    private final UploadCVService uploadCVService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<List<CandidateCVResponse>>> uploadCV(
            @RequestParam(value = "file") List<MultipartFile> file,
            @RequestParam int positionId) {
        return ResponseEntity.ok(uploadCVService.uploadMultipleCVs(file, positionId));
    }
}
