package com.itsm.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.Problems
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/** 401 for requests rejected by the filter chain before reaching a controller. */
@Component
class JsonAuthenticationEntryPoint(private val mapper: ObjectMapper) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) = Problems.write(response, mapper, Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication is required"))
}

/** 403 for authenticated callers rejected by URL-level rules. */
@Component
class JsonAccessDeniedHandler(private val mapper: ObjectMapper) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException
    ) = Problems.write(response, mapper, Problems.of(ErrorCode.FORBIDDEN, "You are not allowed to perform this operation"))
}
