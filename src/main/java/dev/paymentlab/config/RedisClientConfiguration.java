package dev.paymentlab.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RedisClientConfiguration {

    @Bean
    LettuceClientConfigurationBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> {
            // 자동 재연결과 기존 timeout 설정은 보존하되 끊긴 연결에 새 명령을 쌓지 않는다.
            ClientOptions options = builder.build().getClientOptions().orElseGet(ClientOptions::create);
            builder.clientOptions(options.mutate()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
        };
    }
}
