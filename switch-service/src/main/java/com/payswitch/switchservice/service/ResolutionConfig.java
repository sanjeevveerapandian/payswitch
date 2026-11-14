package com.payswitch.switchservice.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ResolutionConfig {

    /**
     * Threads for inline resolution. Bounded: if it is full, POST /pay answers PENDING straight
     * away and the background job picks the payment up later. A flood of stuck payments can never
     * create unlimited threads.
     */
    @Bean
    public AsyncTaskExecutor resolutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("resolve-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }
}
