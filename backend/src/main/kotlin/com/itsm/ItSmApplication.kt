package com.itsm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
class ItSmApplication

fun main(args: Array<String>) {
    runApplication<ItSmApplication>(*args)
}
