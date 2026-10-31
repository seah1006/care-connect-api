package io.github.seah1006.careconnect.common.pagination;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CursorConfiguration {
    @Bean
    CursorCodec cursorCodec(SecretKey jwtKey) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(jwtKey);
        return new CursorCodec(mac.doFinal("care-connect/cursor/v1".getBytes(StandardCharsets.UTF_8)));
    }
}
