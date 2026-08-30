package com.samsung.sure.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class PartnerPlaintextSchemaTest {

    @Test
    void rejectsBinaryStreamThrowableAndCryptographicSourceTypes() {
        assertThatThrownBy(() -> PartnerPlaintextSchema.request("ENCRYPTED_API", byte[].class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerPlaintextSchema.request("ENCRYPTED_API", ByteBuffer.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerPlaintextSchema.request("ENCRYPTED_API", InputStream.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerPlaintextSchema.request("ENCRYPTED_API", Throwable.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerPlaintextSchema.request("ENCRYPTED_API", SecretKey.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerPlaintextSchema.request("ENCRYPTED_API", Object.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
