package com.samsung.sure.partner.observability.testapp.async;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class SyntheticAsyncConfiguration {

    @Bean(name = "syntheticCallbackProcessingExecutor")
    Executor syntheticCallbackProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("synthetic-callback-processing-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(2);
        executor.initialize();
        return executor;
    }
}
