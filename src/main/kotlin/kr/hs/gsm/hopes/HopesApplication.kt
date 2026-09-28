package kr.hs.gsm.hopes

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class HopesApplication

fun main(args: Array<String>) {
    runApplication<HopesApplication>(*args)
}
