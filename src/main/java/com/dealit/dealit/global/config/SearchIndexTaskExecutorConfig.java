package com.dealit.dealit.global.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SearchIndexTaskExecutorConfig {

	@Bean
	public Executor searchIndexTaskExecutor(
		@Value("${app.search.index.executor.core-pool-size:2}") int corePoolSize,
		@Value("${app.search.index.executor.max-pool-size:8}") int maxPoolSize,
		@Value("${app.search.index.executor.queue-capacity:500}") int queueCapacity
	) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("search-index-");
		executor.setCorePoolSize(corePoolSize);
		executor.setMaxPoolSize(maxPoolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.initialize();
		return executor;
	}
}
