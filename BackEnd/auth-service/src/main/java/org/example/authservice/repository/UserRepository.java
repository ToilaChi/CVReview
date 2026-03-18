package org.example.authservice.repository;

import org.example.authservice.models.Users;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<Users, String> {
    @Query("SELECT u FROM Users u WHERE u.phone = :phone")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    Users findByPhone(@Param("phone") String phone);
}
