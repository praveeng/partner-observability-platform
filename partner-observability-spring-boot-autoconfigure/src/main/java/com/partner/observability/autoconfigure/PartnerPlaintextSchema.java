package com.partner.observability.autoconfigure;

import com.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.partner.observability.core.payload.PayloadFieldPolicy;
import com.partner.observability.core.payload.PayloadObjectSchema;
import com.partner.observability.core.payload.PayloadValueType;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.Key;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reflection-free extraction schema for one configured encrypted API leg. Applications register
 * these as beans; a schema may only narrow the configured safe-fields allowlist.
 */
public final class PartnerPlaintextSchema<T> {

    private final String apiName;
    private final ObservationLeg leg;
    private final Class<T> sourceType;
    private final PayloadObjectSchema<T> schema;
    private final List<String> disclosurePaths;

    private PartnerPlaintextSchema(
            String apiName,
            ObservationLeg leg,
            Class<T> sourceType,
            PayloadObjectSchema<T> schema,
            List<String> disclosurePaths) {
        this.apiName = requireToken(apiName);
        this.leg = Objects.requireNonNull(leg, "leg");
        this.sourceType = requirePlaintextDtoType(sourceType);
        this.schema = Objects.requireNonNull(schema, "schema");
        this.disclosurePaths = List.copyOf(disclosurePaths);
        if (leg != ObservationLeg.OUTBOUND_REQUEST && leg != ObservationLeg.OUTBOUND_RESPONSE
                && leg != ObservationLeg.ASYNC_ACKNOWLEDGEMENT) {
            throw new IllegalArgumentException("plaintext schemas support outbound request/response legs only");
        }
    }

    public static <T> Builder<T> request(String configuredApiName, Class<T> sourceType) {
        return new Builder<>(configuredApiName, ObservationLeg.OUTBOUND_REQUEST, sourceType);
    }

    public static <T> Builder<T> response(String configuredApiName, Class<T> sourceType) {
        return new Builder<>(configuredApiName, ObservationLeg.OUTBOUND_RESPONSE, sourceType);
    }

    public static <T> Builder<T> acknowledgement(String configuredApiName, Class<T> sourceType) {
        return new Builder<>(configuredApiName, ObservationLeg.ASYNC_ACKNOWLEDGEMENT, sourceType);
    }

    String apiName() { return apiName; }
    ObservationLeg leg() { return leg; }

    boolean supports(Object candidate) {
        return candidate != null && sourceType.isInstance(candidate);
    }

    boolean isWithin(ObservationDefinition definition) {
        return definition.name().equals(apiName)
                && definition.safeFields().containsAll(disclosurePaths);
    }

    SanitizationResult sanitize(
            Object candidate,
            FailClosedPayloadSanitizer sanitizer,
            PayloadCaptureMode mode) {
        return sanitizer.sanitizeObject(sourceType.cast(candidate), schema, mode);
    }

    private static String requireToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")) {
            throw new IllegalArgumentException("configured API name is invalid");
        }
        return value;
    }

    private static <T> Class<T> requirePlaintextDtoType(Class<T> sourceType) {
        Class<T> checked = Objects.requireNonNull(sourceType, "sourceType");
        if (checked == Object.class
                || checked.isArray()
                || ByteBuffer.class.isAssignableFrom(checked)
                || InputStream.class.isAssignableFrom(checked)
                || Reader.class.isAssignableFrom(checked)
                || java.io.File.class.isAssignableFrom(checked)
                || Path.class.isAssignableFrom(checked)
                || Throwable.class.isAssignableFrom(checked)
                || Key.class.isAssignableFrom(checked)
                || AlgorithmParameterSpec.class.isAssignableFrom(checked)) {
            throw new IllegalArgumentException(
                    "plaintext schema source must be a typed logical DTO, not binary, stream, throwable, or cryptographic material");
        }
        return checked;
    }

    public static final class Builder<T> {
        private final String apiName;
        private final ObservationLeg leg;
        private final Class<T> sourceType;
        private final PayloadObjectSchema.Builder<T> schema;
        private final List<String> disclosurePaths = new ArrayList<>();

        private Builder(String apiName, ObservationLeg leg, Class<T> sourceType) {
            this.apiName = requireToken(apiName);
            this.leg = leg;
            this.sourceType = requirePlaintextDtoType(sourceType);
            this.schema = PayloadObjectSchema.builder(sourceType);
        }

        public Builder<T> allowString(String path, Function<? super T, ?> extractor) {
            return field(path, PayloadFieldPolicy.ALLOW, PayloadValueType.STRING, extractor);
        }

        public Builder<T> allowNumber(String path, Function<? super T, ?> extractor) {
            return field(path, PayloadFieldPolicy.ALLOW, PayloadValueType.NUMBER, extractor);
        }

        public Builder<T> allowBoolean(String path, Function<? super T, ?> extractor) {
            return field(path, PayloadFieldPolicy.ALLOW, PayloadValueType.BOOLEAN, extractor);
        }

        public Builder<T> field(
                String path,
                PayloadFieldPolicy policy,
                PayloadValueType type,
                Function<? super T, ?> extractor) {
            schema.field(path, policy, type, extractor);
            if (policy != PayloadFieldPolicy.REMOVE) {
                disclosurePaths.add(Objects.requireNonNull(path, "path"));
            }
            return this;
        }

        public Builder<T> fieldName(String fieldName, PayloadFieldPolicy policy) {
            schema.fieldName(fieldName, policy);
            return this;
        }

        public PartnerPlaintextSchema<T> build() {
            return new PartnerPlaintextSchema<>(
                    apiName, leg, sourceType, schema.build(), disclosurePaths);
        }
    }
}
