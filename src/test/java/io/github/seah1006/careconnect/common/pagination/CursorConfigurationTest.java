package io.github.seah1006.careconnect.common.pagination;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("INV-API-004")
class CursorConfigurationTest {
    @Test
    void cursorSigningUsesTheDomainSeparatedKey() throws Exception {
        // Synthetic, public test material only.
        var jwtKey = new SecretKeySpec(new byte[32], "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(jwtKey);
        byte[] derivedKey = mac.doFinal("care-connect/cursor/v1".getBytes(StandardCharsets.UTF_8));
        assertThat(derivedKey).hasSize(32).isNotEqualTo(jwtKey.getEncoded());

        var configured = new CursorConfiguration().cursorCodec(jwtKey);
        var derived = new CursorCodec(derivedKey);
        var raw = new CursorCodec(jwtKey.getEncoded());
        var cursor = new Cursor("participants:id", UUID.randomUUID());
        assertThat(configured.decode(derived.encode(cursor))).isEqualTo(cursor);
        assertThat(derived.decode(configured.encode(cursor))).isEqualTo(cursor);
        assertThatThrownBy(() -> configured.decode(raw.encode(cursor)))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> raw.decode(configured.encode(cursor)))
                .isInstanceOf(InvalidCursorException.class);
    }
}
