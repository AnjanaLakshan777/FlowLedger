package com.flowledger.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

/**
 * Turns a failed call to a service into a problem-details answer: 503 when the service cannot be
 * reached, 504 when it does not answer in time. Answers from the services, including their 401
 * and 403, are passed through unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DownstreamErrorFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DownstreamErrorFilter.class);

    private static final Map<String, String> SERVICES = Map.of(
            "/api/v1/identity/", "identity",
            "/api/v1/cash-management/", "cash management",
            "/api/v1/bank-integration/", "bank integration",
            "/api/v1/payment/", "payment",
            "/api/v1/forecast/", "forecast");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            HttpStatus status = statusFor(e);
            if (status == null || response.isCommitted()) {
                throw e;
            }
            String service = SERVICES.entrySet().stream()
                    .filter(entry -> request.getRequestURI().startsWith(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("requested");
            // Only the path and the cause: no headers, so tokens never reach the log.
            log.warn("{} {} failed: {} service {}", request.getMethod(), request.getRequestURI(), service,
                    status == HttpStatus.GATEWAY_TIMEOUT ? "timed out" : "unreachable");
            writeProblem(response, request.getRequestURI(), status, status == HttpStatus.GATEWAY_TIMEOUT
                    ? "The " + service + " service did not answer in time. Please try again later."
                    : "The " + service + " service is unavailable. Please try again later.");
        }
    }

    private static HttpStatus statusFor(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
        }
        return null;
    }

    private static void writeProblem(HttpServletResponse response, String path, HttpStatus status, String detail)
            throws IOException {
        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","instance":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail, path.replace("\\", "").replace("\"", "")));
    }
}
