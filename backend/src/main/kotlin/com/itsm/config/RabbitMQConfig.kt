package com.itsm.config

import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RabbitMQConfig {
    companion object {
        const val EXCHANGE = "itsm.exchange"
    }

    @Bean fun messageConverter(): Jackson2JsonMessageConverter = Jackson2JsonMessageConverter()

    @Bean fun rabbitTemplate(cf: ConnectionFactory): RabbitTemplate =
        RabbitTemplate(cf).also { it.messageConverter = messageConverter() }
}
