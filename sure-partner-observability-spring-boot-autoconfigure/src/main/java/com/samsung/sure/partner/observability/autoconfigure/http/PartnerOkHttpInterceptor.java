package com.samsung.sure.partner.observability.autoconfigure.http;

import com.samsung.sure.partner.observability.autoconfigure.OutboundObservation;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationEngine;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

final class PartnerOkHttpInterceptor implements Interceptor {
    private final PartnerObservationEngine engine;

    PartnerOkHttpInterceptor(PartnerObservationEngine engine) { this.engine = engine; }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String requestType = request.body() == null || request.body().contentType() == null
                ? null : request.body().contentType().toString();
        Optional<OutboundObservation> observation = engine.startOutbound(
                request.url().uri(), request.method(), null, false, requestType,
                headerLength(request.header("Content-Length")), 1);
        try {
            Response response = chain.proceed(request);
            observation.ifPresent(value -> value.complete(
                    response.code(), null, false,
                    response.body() == null || response.body().contentType() == null
                            ? null : response.body().contentType().toString(),
                    headerLength(response.header("Content-Length"))));
            return response;
        } catch (IOException | RuntimeException failure) {
            observation.ifPresent(value -> value.failed(failure));
            throw failure;
        }
    }

    private OptionalLong headerLength(String value) {
        if (value == null) return OptionalLong.empty();
        try {
            long length = Long.parseLong(value);
            return length < 0 ? OptionalLong.empty() : OptionalLong.of(length);
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }
}
