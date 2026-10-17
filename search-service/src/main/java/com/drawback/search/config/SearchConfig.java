package com.drawback.search.config;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SearchConfig {

    @Bean(destroyMethod = "close")
    SolrClient solrClient(SolrProperties props) {
        return new Http2SolrClient.Builder(props.url())
                .withConnectionTimeout(2, TimeUnit.SECONDS)
                .withIdleTimeout(30, TimeUnit.SECONDS)
                .withRequestTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /** Batch listener: one poll => one Solr update request (far cheaper than a request per event). */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> batchFactory(ConsumerFactory<String, Object> consumerFactory,
                                                                        DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /** Runs the independent Solr queries of a global search in parallel. */
    @Bean(name = "searchExecutor")
    ThreadPoolTaskExecutor searchExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("search-");
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(200);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return ex;
    }
}
