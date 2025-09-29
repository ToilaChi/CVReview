package org.example.authservice.services;

import org.example.authservice.models.Users;
import org.example.authservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    public UserDetails loadUserByUsername(String phone) throws UsernameNotFoundException {
        Users user = userRepository.findByPhone(phone);
        if (user == null) {
            throw new UsernameNotFoundException("User not found with username: " + phone);
        }
        return new User(user.getPhone(), user.getPassword(), new ArrayList<>());
    }
}
