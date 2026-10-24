package com.azhukov.agent.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A separate request window for the private owner, including body/auth/storage time. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityDeletionBoundaryFilter extends OncePerRequestFilter {
    public static final String PREFIX="/api/internal/v1/user-deletions/";
    public static final String DEADLINE_ATTRIBUTE=IdentityDeletionBoundaryFilter.class.getName()+".deadline";
    public static boolean privateOwner(HttpServletRequest request){return request.getRequestURI().startsWith(PREFIX);}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !privateOwner(request);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        response.setHeader("Cache-Control","no-store");
        request.setAttribute(DEADLINE_ATTRIBUTE,System.nanoTime()+Duration.ofSeconds(9).toNanos());
        if(request.getContentLengthLong()>16384){response.setStatus(413);return;}
        chain.doFilter(request,response);
    }
}
