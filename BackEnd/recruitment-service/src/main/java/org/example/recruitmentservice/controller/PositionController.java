package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.dto.response.PageResponse;
import org.example.recruitmentservice.dto.request.PositionsRequest;
import org.example.recruitmentservice.dto.response.PositionsResponse;
import org.example.recruitmentservice.services.PositionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/positions")
@RequiredArgsConstructor
public class PositionController {
    private final PositionService positionService;

    @PreAuthorize("hasRole('HR')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PositionsResponse>> createPosition(
            @ModelAttribute PositionsRequest positionsRequest) {
        return ResponseEntity.ok(positionService.createPosition(positionsRequest));
    }

    @PreAuthorize("hasRole('HR')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<PositionsResponse>>> getPositions(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String level
    ) {
        return ResponseEntity.ok(positionService.getPositions(name, language, level));
    }

    @PreAuthorize("hasAnyRole('HR', 'CANDIDATE')")
    @GetMapping("/all")
    public ApiResponse<PageResponse<PositionsResponse>> getAllPositions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return positionService.getAllPositions(page, size);
    }

    @PreAuthorize("hasAnyRole('HR', 'CANDIDATE')")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<PositionsResponse>>> searchPositions(@RequestParam String keyword) {
        return ResponseEntity.ok(positionService.searchPositions(keyword));
    }

    @PreAuthorize("hasRole('HR')")
    @PutMapping("/{positionId}")
    public ResponseEntity<ApiResponse<Object>> updatePosition(
            @PathVariable int positionId,
            @ModelAttribute PositionsRequest positionsRequest) {
        positionService.updatePosition(positionId, positionsRequest);
        return ResponseEntity.ok(new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "Updated successfully"));
    }

    @PreAuthorize("hasRole('HR')")
    @DeleteMapping("")
    public ResponseEntity<ApiResponse<Object>> deletePosition(
            @RequestBody List<Integer> positionIds) {
        positionService.deletePositions(positionIds);
        return ResponseEntity.ok(new ApiResponse<>(
                ErrorCode.SUCCESS.getCode(),
                "Deleted successfully"));
    }
}
