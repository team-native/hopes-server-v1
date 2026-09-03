package kr.hs.gsm.hopes.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.hs.gsm.hopes.domain.UserRepository
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

data class AccessTokenClaims(val email: String, val tokenVersion: Long)

@Component
class AccessTokenService(
    @Value("\${hopes.auth.token-secret}") private val secret: String,
    @Value("\${hopes.auth.token-validity-hours}") private val validityHours: Long,
) {
    init {
        require(secret.toByteArray(StandardCharsets.UTF_8).size >= 32) { "TOKEN_SECRET은 32바이트 이상이어야 합니다" }
        require(validityHours > 0) { "TOKEN_VALIDITY_HOURS는 1 이상이어야 합니다" }
    }

    fun create(email: String, tokenVersion: Long): String {
        val subject = Base64.getUrlEncoder().withoutPadding().encodeToString(email.toByteArray())
        val expires = Instant.now().plusSeconds(validityHours * 3600).epochSecond
        val payload = "$subject.$tokenVersion.$expires"
        return "$payload.${sign(payload)}"
    }

    fun parse(token: String): AccessTokenClaims? = runCatching {
        val parts = token.split('.')
        require(parts.size == 4)
        val payload = "${parts[0]}.${parts[1]}.${parts[2]}"
        require(
            java.security.MessageDigest.isEqual(
                sign(payload).toByteArray(StandardCharsets.US_ASCII),
                parts[3].toByteArray(StandardCharsets.US_ASCII),
            )
        )
        val tokenVersion = parts[1].toLong()
        require(parts[2].toLong() > Instant.now().epochSecond)
        AccessTokenClaims(
            String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
            tokenVersion,
        )
    }.getOrNull()

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    }
}

@Component
class AccessTokenFilter(
    private val tokenService: AccessTokenService,
    private val users: UserRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        val claims = token?.let(tokenService::parse)
        val user = claims?.let { users.findByEmail(it.email) }
        if (claims != null && user?.tokenVersion == claims.tokenVersion) {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(claims.email, null, emptyList())
        }
        filterChain.doFilter(request, response)
    }
}

@Configuration
class SecurityConfig(
    private val tokenFilter: AccessTokenFilter,
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(@Value("\${hopes.cors.allowed-origins}") origins: String): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = origins.split(',').map(String::trim)
            allowedMethods = listOf("GET", "POST", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "X-Hopes-Client")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().also { it.registerCorsConfiguration("/**", config) }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .cors { }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                    "/api/signup/**",
                    "/api/login",
                    "/api/password/**",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                ).permitAll()
                .anyRequest().authenticated()
        }
        .exceptionHandling {
            it.authenticationEntryPoint { _, response, _ ->
                response.status = 401
                response.contentType = "application/json;charset=UTF-8"
                objectMapper.writeValue(response.writer, mapOf("message" to "로그인이 필요합니다"))
            }
        }
        .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter::class.java)
        .build()
}
