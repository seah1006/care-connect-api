package io.github.seah1006.careconnect.participant.application;

import io.github.seah1006.careconnect.common.pagination.CursorCodec;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ParticipantConfiguration {
    @Bean
    CursorCodec participantCursorCodec(SecretKey key) { return new CursorCodec(key.getEncoded()); }
}
