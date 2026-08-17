package com.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.partner.observability.core.model.TransportFailureClass;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import org.junit.jupiter.api.Test;

class TransportFailureClassifierTest {

    @Test
    void usesOnlyThrowableTypesAndNeverCopiesMessages() {
        SSLHandshakeException handshake = new SSLHandshakeException("SYNTHETIC_PRIVATE_CERTIFICATE_DETAIL");
        handshake.initCause(new CertificateException("SYNTHETIC_TRUST_STORE_PASSWORD_DETAIL"));

        assertThat(TransportFailureClassifier.classify(handshake))
                .contains(TransportFailureClass.TLS_CERTIFICATE_VALIDATION);
        assertThat(TransportFailureClassifier.classify(
                        new SSLPeerUnverifiedException("SYNTHETIC_WRONG_HOST_CERTIFICATE_DETAIL")))
                .contains(TransportFailureClass.TLS_CERTIFICATE_VALIDATION);
        assertThat(TransportFailureClassifier.classify(
                        new SSLProtocolException("SYNTHETIC_PROTOCOL_CERTIFICATE_DETAIL")))
                .contains(TransportFailureClass.TLS_PROTOCOL_NEGOTIATION);
        assertThat(TransportFailureClassifier.classify(new IllegalStateException("not a TLS type")))
                .isEmpty();
    }

    @Test
    void traversalIsBoundedAndCycleSafe() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(TransportFailureClassifier.classify(first)).isEmpty();
    }
}
