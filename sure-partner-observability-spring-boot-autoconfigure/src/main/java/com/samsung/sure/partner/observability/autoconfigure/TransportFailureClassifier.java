package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.model.TransportFailureClass;
import java.security.KeyManagementException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;

/** Classifies known TLS types without reading exception messages or certificate state. */
final class TransportFailureClassifier {
    private static final int MAX_CAUSE_DEPTH = 8;

    private TransportFailureClassifier() {}

    static Optional<TransportFailureClass> classify(Throwable failure) {
        boolean certificate = false;
        boolean protocol = false;
        boolean handshake = false;
        boolean configuration = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            if (current instanceof SSLPeerUnverifiedException) {
                // OkHttp also uses this type for certificate pinning, so do not guess hostname failure.
                certificate = true;
            }
            if (current instanceof CertificateException) {
                certificate = true;
            } else if (current instanceof SSLProtocolException) {
                protocol = true;
            } else if (current instanceof SSLHandshakeException || current instanceof SSLException) {
                handshake = true;
            } else if (current instanceof KeyManagementException) {
                configuration = true;
            }
            current = current.getCause();
        }
        if (certificate) return Optional.of(TransportFailureClass.TLS_CERTIFICATE_VALIDATION);
        if (protocol) return Optional.of(TransportFailureClass.TLS_PROTOCOL_NEGOTIATION);
        if (handshake) return Optional.of(TransportFailureClass.TLS_HANDSHAKE);
        if (configuration) return Optional.of(TransportFailureClass.TLS_CONFIGURATION);
        return Optional.empty();
    }
}
