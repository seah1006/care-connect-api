package io.github.seah1006.careconnect.common.crypto;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

@Tag("INV-SENS-002") @Tag("INV-SENS-003") @Tag("INV-SENS-004")
class FieldCryptoTest {
    @Test void canonicalEncodingUsesLengthsAndPreservesAllCodeUnits() {
        assertThat(CryptoEncoding.fields("a", "\ud83d\ude00", "")).containsExactly(new byte[] {
                0, 0, 0, 2, 0, 97, 0, 0, 0, 4, (byte) 0xd8, 0x3d, (byte) 0xde, 0, 0, 0, 0, 0});
        UUID org = UUID.randomUUID(), row = UUID.randomUUID();
        assertThat(new FieldSlot(org, "ab", "c", row).aad()).isNotEqualTo(new FieldSlot(org, "a", "bc", row).aad());
        assertThat(new FieldSlot(org, "", "a", row).aad()).isNotEqualTo(new FieldSlot(org, "a", "", row).aad());
        assertThat(new FieldSlot(org, "\ud800", "c", row).aad()).isNotEqualTo(new FieldSlot(org, "?", "c", row).aad());
        var index = new BlindIndex(CryptoFixture.keys());
        assertThat(index.of(org, "ab", "c")).isNotEqualTo(index.of(org, "a", "bc"));
    }

    @Test void nullEmptySupplementaryAndLongStrings() {
        var cipher = new FieldCipher(CryptoFixture.keys());
        var slot = new FieldSlot(UUID.randomUUID(), "probe", "secret", UUID.randomUUID());
        assertThat(cipher.seal(null, slot)).isNull();
        assertThat(cipher.open(null, slot)).isNull();
        assertThat(cipher.needsRotation(null)).isFalse();
        for (String value : List.of("", "\ud83d\ude00\ud800\udc00", "\ud800", "\udfff", "synthetic-\ud83d\ude00".repeat(10000))) {
            assertThat(cipher.open(cipher.seal(value, slot), slot)).isEqualTo(value);
        }
        String old = cipher.seal("synthetic", slot);
        var rotated = new FieldCipher(new KeyRing("1:" + CryptoFixture.key(1, 32) + ",255:" + CryptoFixture.key(2, 32), "255", CryptoFixture.key(3, 32)));
        assertThat(rotated.needsRotation(old)).isTrue();
        assertThat(rotated.open(old, slot)).isEqualTo("synthetic");
        assertThat(rotated.seal("", slot)).startsWith("v255.");
        assertThat(BlindIndex.normalizePhone("000-1234 5678")).isEqualTo("00012345678");
        assertThat(BlindIndex.normalizePhone(" 000 1234-5678 ")).isEqualTo("00012345678");
        assertThat(BlindIndex.normalizePhone("abc+()")).isEmpty();
    }

    @Test void versionHeaderIsAuthenticatedEvenForIdenticalKeyBytes() {
        var cipher = new FieldCipher(new KeyRing("1:" + CryptoFixture.key(1, 32) + ",2:" + CryptoFixture.key(1, 32), "1", CryptoFixture.key(3, 32)));
        var slot = new FieldSlot(UUID.randomUUID(), "probe", "secret", UUID.randomUUID());
        String sealed = cipher.seal("synthetic", slot);
        assertThatThrownBy(() -> cipher.open(sealed.replace("v1.", "v2."), slot))
                .isExactlyInstanceOf(FieldDecryptionFailed.class).hasMessage("Field decryption failed");
    }

    @Test void malformedUnknownAndNonCanonicalCiphertextsFailIdentically() {
        var cipher = new FieldCipher(CryptoFixture.keys());
        var slot = new FieldSlot(UUID.randomUUID(), "probe", "secret", UUID.randomUUID());
        String valid = cipher.seal("", slot);
        for (String value : List.of("", "v0.AAAA", "v01.AAAA", "v256.AAAA", "v2.AAAA", "v1.A", "v1.***", "v1.AAAA", valid + "=", "v1." + "A".repeat(39))) {
            assertThatThrownBy(() -> cipher.open(value, slot)).isExactlyInstanceOf(FieldDecryptionFailed.class)
                    .hasMessage("Field decryption failed").hasNoCause();
            if (!value.equals("v1." + "A".repeat(39))) {
                assertThatThrownBy(() -> cipher.needsRotation(value)).isExactlyInstanceOf(FieldDecryptionFailed.class);
            }
        }
        assertThatThrownBy(() -> new FieldSlot(null, "t", "c", UUID.randomUUID())).isInstanceOf(NullPointerException.class);
    }

    @Test void invalidKeysPreventStartupWithoutExposingSecrets() {
        String key = CryptoFixture.key(1, 32), index = CryptoFixture.key(3, 32);
        var invalid = List.of(
                new String[]{"", "1", index}, new String[]{"1:" + key + ",", "1", index},
                new String[]{"invalid", "1", index}, new String[]{"1:***", "1", index},
                new String[]{"1:" + key + ":extra", "1", index},
                new String[]{"1:" + key + ",1:" + key, "1", index},
                new String[]{"0:" + key, "0", index}, new String[]{"256:" + key, "256", index},
                new String[]{"-1:" + key, "1", index}, new String[]{"abc:" + key, "1", index},
                new String[]{"1:" + CryptoFixture.key(1, 31), "1", index},
                new String[]{"1:" + CryptoFixture.key(1, 33), "1", index},
                new String[]{"1:" + key, "2", index}, new String[]{"1:" + key, "bad", index},
                new String[]{"1:" + key, "1", "***"}, new String[]{"1:" + key, "1", CryptoFixture.key(3, 31)},
                new String[]{"1:" + key, "1", key},
                new String[]{"1:" + key + ",2:" + index, "1", index});
        for (String[] args : invalid) {
            assertThatThrownBy(() -> new KeyRing(args[0], args[1], args[2]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid field key configuration").hasNoCause();
            new ApplicationContextRunner().withUserConfiguration(FieldCryptoConfiguration.class)
                    .withPropertyValues("security.field-encryption.keys=" + args[0],
                            "security.field-encryption.active-version=" + args[1], "security.field-index-key=" + args[2])
                    .run(context -> {
                        assertThat(context).hasFailed();
                        Throwable failure = context.getStartupFailure();
                        while (failure != null) {
                            assertThat(failure.getMessage()).doesNotContain(key, index);
                            failure = failure.getCause();
                        }
                    });
        }
        var runner = new ApplicationContextRunner().withUserConfiguration(FieldCryptoConfiguration.class);
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("security.field-encryption.keys=1:" + key,
                "security.field-encryption.active-version=1", "security.field-index-key=" + index)
                .run(context -> {
                    assertThat(context).hasSingleBean(FieldCipher.class).hasSingleBean(BlindIndex.class);
                    UUID org = UUID.randomUUID();
                    assertThat(context.getBean(BlindIndex.class).of(org, "p", "v"))
                            .isEqualTo(new BlindIndex(CryptoFixture.keys()).of(org, "p", "v"));
                });
    }
}
