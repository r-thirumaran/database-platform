package org.dbplatform.controlplane.config;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AsyncConfig {
    /** Scheduler for retention, governance and the collector tick. */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(3);
        s.setThreadNamePrefix("dbp-sched-");
        s.setDaemon(true);
        return s;
    }

    /** Executor running collector jobs (virtual threads: a blocked JDBC call costs nothing). */
    @Bean(name = "collectorExecutor", destroyMethod = "close")
    public java.util.concurrent.ExecutorService collectorExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dbp-collector-", 0).factory());
    }
}
