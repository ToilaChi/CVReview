package org.example.recruitmentservice.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonlibrary.dto.ApiResponse;
import org.example.recruitmentservice.dto.request.PositionsRequest;
import org.example.recruitmentservice.dto.response.PositionsResponse;
import org.example.recruitmentservice.services.PositionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/positions")
@RequiredArgsConstructor
public class PositionController {
    private final PositionService positionService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PositionsResponse>> createPosition(
            @ModelAttribute PositionsRequest positionsRequest) {
        return ResponseEntity.ok(positionService.createPosition(positionsRequest));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PositionsResponse>>> getPositions(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String level
    ) {
        return ResponseEntity.ok(positionService.getPositions(name, language, level));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<PositionsResponse>>> searchPositions(@RequestParam String keyword) {
        return ResponseEntity.ok(positionService.searchPositions(keyword));
    }
}
