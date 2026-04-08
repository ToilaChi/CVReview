package org.example.authservice.controller;

import org.example.authservice.dto.request.RegisterHrRequest;
import org.example.authservice.dto.response.Userdata;
import org.example.authservice.services.AuthService;
import org.example.commonlibrary.dto.response.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private AuthService authService;

    @PostMapping("/hr-accounts")
    public ApiResponse<Userdata> registerHrAccount(@RequestBody RegisterHrRequest request) {
        return authService.registerHr(request);
    }
}
