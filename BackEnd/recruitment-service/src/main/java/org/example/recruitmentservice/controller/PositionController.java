package org.example.recruitmentservice.controller;

import org.example.commonlibrary.dto.ApiResponse;
import org.example.recruitmentservice.dto.request.PositionsRequest;
import org.example.recruitmentservice.dto.response.PositionsResponse;
import org.example.recruitmentservice.services.PositionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/positions")
public class PositionController {
    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PositionsResponse>> createPosition(
            @ModelAttribute PositionsRequest positionsRequest) {
        return ResponseEntity.ok(positionService.createPosition(positionsRequest));
    }
}
