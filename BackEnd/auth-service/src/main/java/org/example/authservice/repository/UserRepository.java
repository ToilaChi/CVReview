package org.example.authservice.repository;

import org.example.authservice.models.Users;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<Users, String> {
    Users findByPhone(String phone);
}
