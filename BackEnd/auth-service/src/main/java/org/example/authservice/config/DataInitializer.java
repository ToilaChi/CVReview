package org.example.authservice.config;

import org.example.authservice.models.Role;
import org.example.authservice.models.Users;
import org.example.authservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        String adminPhone = "0000000000";
        if (userRepository.findByPhone(adminPhone) == null) {
            Users admin = Users.builder()
                    .name("Super Admin")
                    .email("admin@system.local")
                    .phone(adminPhone)
                    .password(passwordEncoder.encode("Admin@123"))
                    .role(Role.ADMIN)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            userRepository.save(admin);
            System.out.println("Super admin account created successfully.");
        }
    }
}
