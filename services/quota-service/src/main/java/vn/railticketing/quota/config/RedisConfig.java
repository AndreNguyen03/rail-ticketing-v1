package vn.railticketing.quota.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    @org.springframework.context.annotation.Primary
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new StringRedisSerializer());
        t.setHashKeySerializer(new StringRedisSerializer());
        t.setHashValueSerializer(new StringRedisSerializer());
        return t;
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List<Object>> checkAndReserveScript() {
        DefaultRedisScript<List<Object>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/quota_check_and_reserve.lua"));
        script.setResultType((Class<List<Object>>) (Class<?>) List.class);
        return script;
    }

    @Bean
    public RedisScript<Long> releaseScript() {
        return RedisScript.of(new ClassPathResource("lua/quota_release.lua"), Long.class);
    }
}
