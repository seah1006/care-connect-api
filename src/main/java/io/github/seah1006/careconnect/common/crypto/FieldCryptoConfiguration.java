package io.github.seah1006.careconnect.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FieldCryptoConfiguration {
    @Bean
    KeyRing fieldKeys(@Value("${security.field-encryption.keys:}") String keys,
            @Value("${security.field-encryption.active-version:}") String active,
            @Value("${security.field-index-key:}") String index) {
        return new KeyRing(keys, active, index);
    }

    @Bean FieldCipher fieldCipher(KeyRing keys) { return new FieldCipher(keys); }
    @Bean BlindIndex blindIndex(KeyRing keys) { return new BlindIndex(keys); }
}
