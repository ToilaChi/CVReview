package org.example.recruitmentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_ROLE = "X-User-Role";
    public static final String HEADER_PRINCIPAL = "X-User-Phone";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        // Nếu đã có authentication thì không override
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String roleHeader = request.getHeader(HEADER_ROLE);
            String principal = request.getHeader(HEADER_PRINCIPAL);

            if (roleHeader != null && !roleHeader.isBlank()) {
                List<SimpleGrantedAuthority> authorities = Stream.of(roleHeader.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> {
                            if (s.startsWith("ROLE_")) return s;
                            return "ROLE_" + s;
                        })
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());

                if (principal == null) principal = "anonymous-from-gateway";

                Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }
}
