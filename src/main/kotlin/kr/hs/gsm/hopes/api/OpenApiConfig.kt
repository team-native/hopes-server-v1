package kr.hs.gsm.hopes.api

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun hopesOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Hopes API")
                .description("광주소프트웨어마이스터고 학생을 위한 AI 상담 서비스 API")
                .version("1.0.0")
        )
        .servers(
            listOf(
                Server().url("http://ssh.gsmsv.site:25105").description("운영 서버"),
                Server().url("http://localhost:8080").description("로컬 서버"),
            )
        )
        .components(
            Components().addSecuritySchemes(
                "bearerAuth",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("HMAC-SHA256 access token")
                    .description("로그인 응답의 accessToken을 입력합니다."),
            )
        )
}
