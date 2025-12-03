//package org.example.authservice.security;
//
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import org.example.authservice.services.TokenBlackListService;
//import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
//import org.springframework.security.core.authority.SimpleGrantedAuthority;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.security.core.userdetails.UserDetailsService;
//import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
//import org.springframework.stereotype.Component;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import java.io.IOException;
//import java.util.List;
//
//@Component
//public class JwtFilter extends OncePerRequestFilter {
//    private final JwtUtil jwtUtil;
//    private final TokenBlackListService tokenBlackListService;
//    private final UserDetailsService userDetailsService;
//
//    public JwtFilter(JwtUtil jwtUtil,
//                     TokenBlackListService tokenBlackListService,
//                     UserDetailsService userDetailsService) {
//        this.jwtUtil = jwtUtil;
//        this.tokenBlackListService = tokenBlackListService;
//        this.userDetailsService = userDetailsService;
//    }
//
//    @Override
//    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
//        final String authorizationHeader = request.getHeader("Authorization");
//
//        String phone = null;
//        String token = null;
//
//        logger.debug("Authorization header: " + authorizationHeader);
//
//        if(authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
//            token = authorizationHeader.substring(7);
//            logger.debug("Extracted token: " + token);
//
//            //Check blacklist
//            if(tokenBlackListService.isBlacklisted(token)) {
//                logger.debug("Token is blacklisted: " + token);
//                filterChain.doFilter(request, response);
//                return;
//            }
//
//            try {
//                phone = jwtUtil.validateTokenAndRetrieveSubject(token);
//                logger.debug("Phone: " + phone);
//            }
//            catch(Exception e) {
//                logger.debug("Invalid token: " + token);
//
//                //401 error
//                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//                response.setContentType("application/json");
//                response.setCharacterEncoding("UTF-8");
//                response.getWriter().write("{\"message\": \"Token không hợp lệ hoặc đã hết hạn\"}");
//                return;
//            }
//        }
//
//        //Nếu có người dùng nhưng chưa được xác thực
//        if (phone != null && SecurityContextHolder.getContext().getAuthentication() == null) {
//            // Load user from db
//            UserDetails userDetails = userDetailsService.loadUserByUsername(phone);
//
//            //Get role
//            String role = jwtUtil.extractRole(token);
//            List<SimpleGrantedAuthority> authorities = List.of(
//                    new SimpleGrantedAuthority("ROLE_" + role)
//            );
//
//            UsernamePasswordAuthenticationToken authentication =
//                    new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
//            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
//            SecurityContextHolder.getContext().setAuthentication(authentication);
//        }
//        filterChain.doFilter(request, response);
//    }
//}
