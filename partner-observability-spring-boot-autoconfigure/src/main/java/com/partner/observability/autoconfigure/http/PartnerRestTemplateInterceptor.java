package com.partner.observability.autoconfigure.http;

import com.partner.observability.autoconfigure.OutboundObservation;
import com.partner.observability.autoconfigure.PartnerObservationEngine;
import com.partner.observability.autoconfigure.UnavailableBody;
import com.partner.observability.core.payload.PayloadStatus;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

final class PartnerRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    private static final int MAX_CAPTURE_BYTES = 64 * 1024;
    private final PartnerObservationEngine engine;
    private final List<OutboundAttemptResolver> attemptResolvers;

    PartnerRestTemplateInterceptor(PartnerObservationEngine engine, List<OutboundAttemptResolver> attemptResolvers) {
        this.engine = engine;
        this.attemptResolvers = List.copyOf(attemptResolvers);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Optional<OutboundObservation> observation = engine.startOutbound(
                request.getURI(), request.getMethodValue(), body, true, contentType(request.getHeaders()),
                OptionalLong.of(body.length), attempt(request.getURI(), request.getMethodValue()));
        try {
            ClientHttpResponse response = execution.execute(request, body);
            if (observation.isEmpty()) {
                return response;
            }
            return new ObservedResponse(response, observation.get());
        } catch (IOException | RuntimeException failure) {
            observation.ifPresent(value -> value.failed(failure));
            throw failure;
        }
    }

    private int attempt(URI endpoint, String method) {
        String apiName = engine.resolveOutboundApiName(endpoint, method).orElse("unknown");
        for (OutboundAttemptResolver resolver : attemptResolvers) {
            try {
                int attempt = resolver.attempt(apiName, endpoint);
                if (attempt > 0) return attempt;
            } catch (RuntimeException ignored) {
                // Retry metadata is optional and cannot affect the client.
            }
        }
        return 1;
    }

    private static String contentType(HttpHeaders headers) {
        return headers.getContentType() == null ? null : headers.getContentType().toString();
    }

    private static OptionalLong declaredSize(HttpHeaders headers) {
        long length = headers.getContentLength();
        return length < 0 ? OptionalLong.empty() : OptionalLong.of(length);
    }

    private static final class ObservedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final OutboundObservation observation;
        private final AtomicBoolean completed = new AtomicBoolean();
        private InputStream body;

        private ObservedResponse(ClientHttpResponse delegate, OutboundObservation observation) {
            this.delegate = delegate;
            this.observation = observation;
        }

        @Override
        public org.springframework.http.HttpStatus getStatusCode() throws IOException {
            try {
                return delegate.getStatusCode();
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                throw failure;
            }
        }

        @Override
        public int getRawStatusCode() throws IOException {
            try {
                return delegate.getRawStatusCode();
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                throw failure;
            }
        }

        @Override
        public String getStatusText() throws IOException {
            try {
                return delegate.getStatusText();
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                throw failure;
            }
        }

        @Override
        public HttpHeaders getHeaders() {
            try {
                return delegate.getHeaders();
            } catch (RuntimeException failure) {
                fail(failure);
                throw failure;
            }
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                try {
                    OptionalLong size = declaredSize(delegate.getHeaders());
                    body = new CapturingInputStream(
                            delegate.getBody(), size.isPresent() && size.getAsLong() > MAX_CAPTURE_BYTES);
                } catch (IOException | RuntimeException failure) {
                    fail(failure);
                    throw failure;
                }
            }
            return body;
        }

        @Override
        public void close() {
            if (body instanceof CapturingInputStream capturing) {
                capturing.finishIfDeclaredLengthConsumed();
            }
            complete(new UnavailableBody(PayloadStatus.STREAM_NOT_CONSUMED), true);
            delegate.close();
        }

        private void complete(Object candidate, boolean supported) {
            if (completed.get()) return;
            int status;
            try {
                status = delegate.getRawStatusCode();
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                return;
            }
            if (!completed.compareAndSet(false, true)) return;
            try {
                observation.complete(status, candidate, supported,
                        contentType(delegate.getHeaders()), declaredSize(delegate.getHeaders()));
            } catch (RuntimeException ignored) {
                // Observation cannot alter the response already owned by RestTemplate.
            }
        }

        private void fail(Throwable failure) {
            if (completed.compareAndSet(false, true)) {
                observation.failed(failure);
            }
        }

        private final class CapturingInputStream extends FilterInputStream {
            private ByteArrayOutputStream captured = new ByteArrayOutputStream(1024);
            private boolean oversized;

            private CapturingInputStream(InputStream input, boolean oversized) {
                super(input);
                this.oversized = oversized;
                if (oversized) captured = null;
            }

            @Override public int read() throws IOException {
                try {
                    int value = super.read();
                    if (value < 0) finish(); else append((byte) value);
                    return value;
                } catch (IOException failure) {
                    fail(failure);
                    throw failure;
                }
            }

            @Override public int read(byte[] target, int offset, int length) throws IOException {
                try {
                    int count = super.read(target, offset, length);
                    if (count < 0) finish(); else append(target, offset, count);
                    return count;
                } catch (IOException failure) {
                    fail(failure);
                    throw failure;
                }
            }

            @Override public void close() throws IOException {
                try {
                    super.close();
                } catch (IOException failure) {
                    fail(failure);
                    throw failure;
                } finally {
                    finish();
                }
            }

            private void append(byte value) {
                if (oversized) return;
                if (captured.size() == MAX_CAPTURE_BYTES) {
                    oversized = true;
                    captured = null;
                } else {
                    captured.write(value);
                }
            }

            private void append(byte[] bytes, int offset, int count) {
                if (oversized) return;
                if (captured.size() + count > MAX_CAPTURE_BYTES) {
                    oversized = true;
                    captured = null;
                } else {
                    captured.write(bytes, offset, count);
                }
            }

            private void finish() {
                if (oversized) {
                    complete(new UnavailableBody(PayloadStatus.OVERSIZE), true);
                } else {
                    complete(captured.toByteArray(), true);
                }
            }

            private void finishIfDeclaredLengthConsumed() {
                OptionalLong declared = declaredSize(delegate.getHeaders());
                if (oversized) {
                    finish();
                } else if (declared.isPresent() && declared.getAsLong() == captured.size()) {
                    finish();
                }
            }
        }
    }
}
