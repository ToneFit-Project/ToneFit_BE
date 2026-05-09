package com.example.tonefitserver.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class VisitSessionIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Visit-Session-Id";

    private final RequestContext requestContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        requestContext.setVisitSessionId(request.getHeader(HEADER));
        filterChain.doFilter(request, response);
    }
}
