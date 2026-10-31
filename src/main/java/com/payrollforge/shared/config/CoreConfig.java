package com.payrollforge.shared.config;

import com.payrollforge.outbox.application.Outbox;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic attendanceTopic() {
        return TopicBuilder.name(Outbox.ATTENDANCE).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic leaveTopic() {
        return TopicBuilder.name(Outbox.LEAVE).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic payrollTopic() {
        return TopicBuilder.name(Outbox.PAYROLL).partitions(1).replicas(1).build();
    }
}
