package com.partner.observability.core.payload;

import com.partner.observability.core.policy.PayloadCaptureMode;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, allowlist-first sanitizer. It never reflects, serializes, or calls arbitrary toString. */
public final class FailClosedPayloadSanitizer implements PayloadSanitizer {

    private static final Set<String> REMOVAL_ALIASES = Set.of(
            "authorization", "proxyauthorization", "auth", "credential", "credentials",
            "password", "passcode", "secret", "clientsecret", "token", "accesstoken",
            "refreshtoken", "jwt", "cookie", "setcookie", "session", "sessionid",
            "apikey", "privatekey", "signingkey", "encryptionkey", "otp", "onetimepassword",
            "verificationcode", "pin", "card", "cardnumber", "pan", "cvv", "cvc", "cid",
            "trackdata", "magneticstripe", "expiry");
    private static final Set<String> PHONE_ALIASES = Set.of("phone", "phonenumber", "mobile", "mobilephone");
    private static final Set<String> EMAIL_ALIASES = Set.of("email", "emailaddress");
    private static final Set<String> ACCOUNT_ALIASES = Set.of("account", "accountnumber", "bankaccount", "iban");
    private static final Set<String> NATIONAL_ID_ALIASES = Set.of(
            "nationalid", "nationalidentifier", "governmentid", "taxid", "taxidentifier");
    private static final Set<String> ADDRESS_ALIASES = Set.of("address", "postaladdress", "streetaddress");
    private static final Set<String> DOCUMENT_ALIASES = Set.of(
            "document", "documents", "attachment", "attachments", "signature", "pdf", "image", "photo");

    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern BASE64_STANDARD = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    private final PayloadLimits limits;

    public FailClosedPayloadSanitizer() {
        this(PayloadLimits.defaults());
    }

    public FailClosedPayloadSanitizer(PayloadLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    @Override
    public SanitizationResult sanitize(PayloadInput input, PayloadSchema schema, PayloadCaptureMode mode) {
        try {
            java.util.Objects.requireNonNull(input, "input");
            java.util.Objects.requireNonNull(schema, "schema");
            java.util.Objects.requireNonNull(mode, "mode");
            if (mode == PayloadCaptureMode.NONE || mode == PayloadCaptureMode.METADATA_ONLY) {
                return SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);
            }
            if (input.isMalformed()) {
                return SanitizationResult.rejected(PayloadStatus.MALFORMED);
            }
            if (input.isEncrypted()) {
                return binaryOmission(BinaryKind.ENCRYPTED, input);
            }
            if (schema.isEmpty()) {
                return SanitizationResult.omitted(PayloadStatus.NOT_ALLOWLISTED);
            }
            if (input.declaredSizeBytes().isPresent()
                    && input.declaredSizeBytes().getAsLong() > limits.rawCandidateBytes()) {
                return SanitizationResult.omitted(PayloadStatus.OVERSIZE);
            }
            Optional<BinaryKind> contentKind = binaryContentType(input.contentType());
            if (contentKind.isPresent()) {
                return binaryOmission(contentKind.get(), input);
            }
            if (isBinaryType(input.value())) {
                return binaryOmission(binaryKind(input.value()), input);
            }
            if (input.value() instanceof String text
                    && text.getBytes(StandardCharsets.UTF_8).length > limits.rawCandidateBytes()) {
                return SanitizationResult.omitted(PayloadStatus.OVERSIZE);
            }

            Traversal traversal = new Traversal(schema, input);
            Optional<SanitizedValue> value = traversal.visit(input.value(), "", 1, null);
            if (value.isEmpty()) {
                PayloadStatus status = traversal.binaryCount > 0
                        ? statusFor(traversal.binaryKind)
                        : PayloadStatus.NOT_ALLOWLISTED;
                if (traversal.binaryCount > 0) {
                    return SanitizationResult.omitted(status, traversal.binaryMetadata());
                }
                return SanitizationResult.omitted(status);
            }
            int bytes = jsonBytes(value.get());
            if (bytes > limits.safePayloadBytes()) {
                return SanitizationResult.omitted(PayloadStatus.OVERSIZE);
            }
            return SanitizationResult.captured(
                    new SanitizedPayload(value.get(), bytes),
                    traversal.binaryCount == 0 ? Optional.empty() : Optional.of(traversal.binaryMetadata()),
                    traversal.removed,
                    traversal.masked,
                    traversal.omitted);
        } catch (PayloadOversizeException exception) {
            return SanitizationResult.omitted(PayloadStatus.OVERSIZE);
        } catch (RuntimeException exception) {
            return SanitizationResult.rejected(PayloadStatus.MALFORMED);
        }
    }

    private SanitizationResult binaryOmission(BinaryKind kind, PayloadInput input) {
        PayloadStatus status = statusFor(kind);
        OmittedBinaryMetadata metadata = new OmittedBinaryMetadata(
                kind, 1, input.declaredSizeBytes(), input.contentType());
        return SanitizationResult.omitted(status, metadata);
    }

    private PayloadStatus statusFor(BinaryKind kind) {
        return kind == BinaryKind.BASE64 || kind == BinaryKind.UNKNOWN_ENCODED || kind == BinaryKind.ENCRYPTED
                ? PayloadStatus.BASE64
                : PayloadStatus.BINARY;
    }

    private Optional<BinaryKind> binaryContentType(Optional<String> contentType) {
        if (contentType.isEmpty()) {
            return Optional.empty();
        }
        String value = contentType.get();
        if (value.equals("application/pdf")) {
            return Optional.of(BinaryKind.PDF);
        }
        if (value.startsWith("image/")) {
            return Optional.of(BinaryKind.IMAGE);
        }
        if (value.equals("application/octet-stream") || value.startsWith("multipart/")
                || value.startsWith("audio/") || value.startsWith("video/")
                || value.contains("zip") || value.contains("protobuf") || value.contains("font")) {
            return Optional.of(BinaryKind.BINARY);
        }
        if (value.equals("application/json") || value.endsWith("+json") || value.startsWith("text/")) {
            return Optional.empty();
        }
        return Optional.of(BinaryKind.UNKNOWN_ENCODED);
    }

    private boolean isBinaryType(Object value) {
        return value instanceof byte[]
                || value instanceof ByteBuffer
                || value instanceof InputStream
                || value instanceof Reader
                || value instanceof java.io.File
                || value instanceof Path;
    }

    private BinaryKind binaryKind(Object value) {
        return value instanceof byte[] || value instanceof ByteBuffer ? BinaryKind.BINARY : BinaryKind.DOCUMENT;
    }

    private final class Traversal {
        private final PayloadSchema schema;
        private final PayloadInput input;
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private int nodes;
        private int removed;
        private int masked;
        private int omitted;
        private int binaryCount;
        private BinaryKind binaryKind;

        private Traversal(PayloadSchema schema, PayloadInput input) {
            this.schema = schema;
            this.input = input;
        }

        private Optional<SanitizedValue> visit(
                Object candidate, String path, int depth, PayloadFieldPolicy inheritedPolicy) {
            if (++nodes > limits.totalNodes()) {
                throw new PayloadOversizeException();
            }
            if (depth > limits.objectDepth()) {
                omitted++;
                return Optional.empty();
            }
            if (candidate == null) {
                Optional<PayloadFieldPolicy> nullPolicy = policy(path, inheritedPolicy);
                if (nullPolicy.orElse(null) == PayloadFieldPolicy.REMOVE) {
                    removed++;
                    return Optional.empty();
                }
                return nullPolicy.map(ignored -> SanitizedNullValue.INSTANCE);
            }
            if (policy(path, inheritedPolicy).orElse(null) == PayloadFieldPolicy.REMOVE) {
                removed++;
                return Optional.empty();
            }
            if (isBinaryType(candidate)) {
                recordBinary(binaryKind(candidate));
                return Optional.empty();
            }
            if (candidate instanceof Map<?, ?> map) {
                return visitMap(map, path, depth);
            }
            if (candidate instanceof List<?> list) {
                return visitList(list, path, depth, inheritedPolicy);
            }
            PayloadFieldPolicy fieldPolicy = policy(path, inheritedPolicy).orElse(null);
            if (fieldPolicy == null) {
                omitted++;
                return Optional.empty();
            }
            if (fieldPolicy == PayloadFieldPolicy.REMOVE) {
                removed++;
                return Optional.empty();
            }
            return visitScalar(candidate, fieldPolicy);
        }

        private Optional<SanitizedValue> visitMap(Map<?, ?> map, String path, int depth) {
            enter(map);
            try {
                Map<String, SanitizedValue> safe = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String rawKey)) {
                        omitted++;
                        continue;
                    }
                    String key = PayloadSchema.normalizeKey(rawKey);
                    if (key.isEmpty()) {
                        omitted++;
                        continue;
                    }
                    String childPath = path.isEmpty() ? key : path + "." + key;
                    boolean known = schema.policyFor(childPath).isPresent() || schema.hasDescendant(childPath);
                    if (!known) {
                        omitted++;
                        continue;
                    }
                    if (isRemovalAlias(key)) {
                        removed++;
                        continue;
                    }
                    BinaryKind keyKind = documentKind(key);
                    if (keyKind != null) {
                        recordBinary(keyKind);
                        continue;
                    }
                    PayloadFieldPolicy builtInMask = builtInMask(key);
                    if (builtInMask != null) {
                        safe.put(key, mask(entry.getValue(), builtInMask));
                        masked++;
                        continue;
                    }
                    visit(entry.getValue(), childPath, depth + 1, schema.policyFor(childPath).orElse(null))
                            .ifPresent(value -> safe.put(key, value));
                }
                return safe.isEmpty() ? Optional.empty() : Optional.of(new SanitizedObjectValue(safe));
            } finally {
                leave(map);
            }
        }

        private Optional<SanitizedValue> visitList(
                List<?> list, String path, int depth, PayloadFieldPolicy inheritedPolicy) {
            if (list.size() > limits.arrayElements()) {
                throw new PayloadOversizeException();
            }
            enter(list);
            try {
                List<SanitizedValue> safe = new ArrayList<>();
                String elementPath = path + "[]";
                PayloadFieldPolicy elementPolicy = schema.policyFor(elementPath)
                        .orElse(schema.policyFor(path).orElse(inheritedPolicy));
                for (Object value : list) {
                    visit(value, elementPath, depth + 1, elementPolicy).ifPresent(safe::add);
                }
                return Optional.of(new SanitizedArrayValue(safe));
            } finally {
                leave(list);
            }
        }

        private Optional<SanitizedValue> visitScalar(Object value, PayloadFieldPolicy policy) {
            if (policy != PayloadFieldPolicy.ALLOW) {
                masked++;
                return Optional.of(mask(value, policy));
            }
            if (value instanceof String text) {
                if (containsUnsafeControls(text)) {
                    omitted++;
                    return Optional.empty();
                }
                if (isSecretValue(text)) {
                    removed++;
                    return Optional.empty();
                }
                BinaryKind encoded = encodedKind(text);
                if (encoded != null) {
                    recordBinary(encoded);
                    return Optional.empty();
                }
                return Optional.of(new SanitizedStringValue(truncateUtf8(text, limits.stringBytes())));
            }
            if (value instanceof Boolean bool) {
                return Optional.of(new SanitizedBooleanValue(bool));
            }
            if (value instanceof BigDecimal decimal) {
                return Optional.of(new SanitizedNumberValue(decimal));
            }
            if (value instanceof BigInteger integer) {
                return Optional.of(new SanitizedNumberValue(new BigDecimal(integer)));
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return Optional.of(new SanitizedNumberValue(BigDecimal.valueOf(((Number) value).longValue())));
            }
            if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (Double.isFinite(number)) {
                    return Optional.of(new SanitizedNumberValue(BigDecimal.valueOf(number)));
                }
            }
            omitted++;
            return Optional.empty();
        }

        private Optional<PayloadFieldPolicy> policy(String path, PayloadFieldPolicy fallback) {
            Optional<PayloadFieldPolicy> configured = schema.policyFor(path);
            return configured.isPresent() ? configured : Optional.ofNullable(fallback);
        }

        private SanitizedValue mask(Object value, PayloadFieldPolicy policy) {
            String text = value instanceof String string ? string : "";
            return switch (policy) {
                case MASK_PHONE -> new SanitizedStringValue("******" + lastAlphanumeric(text, 4));
                case MASK_EMAIL -> new SanitizedStringValue(maskEmail(text));
                case MASK_ACCOUNT -> new SanitizedStringValue("********" + lastAlphanumeric(text, 4));
                case MASK_NATIONAL_IDENTIFIER -> new SanitizedStringValue("******" + lastAlphanumeric(text, 4));
                case MASK_ADDRESS -> new SanitizedStringValue("[MASKED_ADDRESS]");
                case REMOVE -> throw new IllegalArgumentException("REMOVE cannot produce a value");
                case ALLOW -> throw new IllegalArgumentException("ALLOW is not a masking policy");
            };
        }

        private void recordBinary(BinaryKind kind) {
            binaryCount++;
            omitted++;
            if (binaryKind == null) {
                binaryKind = kind;
            }
        }

        private OmittedBinaryMetadata binaryMetadata() {
            return new OmittedBinaryMetadata(
                    binaryKind,
                    Math.min(binaryCount, PayloadLimits.HARD_MAX_TOTAL_NODES),
                    input.declaredSizeBytes(),
                    input.contentType());
        }

        private void enter(Object value) {
            if (active.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic payload");
            }
        }

        private void leave(Object value) {
            active.remove(value);
        }
    }

    private boolean isRemovalAlias(String key) {
        return REMOVAL_ALIASES.contains(key);
    }

    private PayloadFieldPolicy builtInMask(String key) {
        if (PHONE_ALIASES.contains(key)) {
            return PayloadFieldPolicy.MASK_PHONE;
        }
        if (EMAIL_ALIASES.contains(key)) {
            return PayloadFieldPolicy.MASK_EMAIL;
        }
        if (ACCOUNT_ALIASES.contains(key)) {
            return PayloadFieldPolicy.MASK_ACCOUNT;
        }
        if (NATIONAL_ID_ALIASES.contains(key)) {
            return PayloadFieldPolicy.MASK_NATIONAL_IDENTIFIER;
        }
        if (ADDRESS_ALIASES.contains(key)) {
            return PayloadFieldPolicy.MASK_ADDRESS;
        }
        return null;
    }

    private BinaryKind documentKind(String key) {
        if (!DOCUMENT_ALIASES.contains(key)) {
            return null;
        }
        if (key.contains("pdf")) {
            return BinaryKind.PDF;
        }
        if (key.contains("image") || key.contains("photo")) {
            return BinaryKind.IMAGE;
        }
        return BinaryKind.DOCUMENT;
    }

    private boolean isSecretValue(String value) {
        String normalized = value.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.startsWith("bearer ")
                || lower.startsWith("basic ")
                || lower.startsWith("-----begin ")
                || JWT.matcher(normalized).matches()
                || isPaymentCard(normalized);
    }

    private BinaryKind encodedKind(String value) {
        String normalized = value.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:")) {
            return BinaryKind.BASE64;
        }
        if (normalized.length() > limits.rawCandidateBytes()) {
            throw new PayloadOversizeException();
        }
        if (normalized.length() >= 12 && normalized.length() % 4 == 0
                && BASE64_STANDARD.matcher(normalized).matches()) {
            Optional<byte[]> decoded = decodeBounded(normalized, false);
            if (decoded.isPresent() && (hasBinarySignature(decoded.get())
                    || (normalized.length() >= 256 && looksBinary(decoded.get())))) {
                return BinaryKind.BASE64;
            }
        }
        if (normalized.length() >= 256 && BASE64_URL.matcher(normalized).matches()
                && decodeBounded(normalized, true).isPresent()) {
            return BinaryKind.UNKNOWN_ENCODED;
        }
        return null;
    }

    private Optional<byte[]> decodeBounded(String value, boolean urlSafe) {
        try {
            byte[] decoded = (urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(value);
            return decoded.length > 0 && decoded.length <= limits.rawCandidateBytes()
                    ? Optional.of(decoded)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean hasBinarySignature(byte[] value) {
        return startsWith(value, new byte[] {'%', 'P', 'D', 'F'})
                || startsWith(value, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})
                || startsWith(value, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
    }

    private boolean startsWith(byte[] value, byte[] signature) {
        if (value.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (value[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean looksBinary(byte[] value) {
        int nonText = 0;
        for (byte item : value) {
            int unsigned = item & 0xff;
            if (unsigned == 0 || unsigned > 0x7e || (unsigned < 0x20 && unsigned != '\n' && unsigned != '\r' && unsigned != '\t')) {
                nonText++;
            }
        }
        return nonText * 10 >= value.length;
    }

    private boolean isPaymentCard(String value) {
        String digits = value.replaceAll("[ -]", "");
        if (!digits.matches("[0-9]{13,19}")) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private boolean containsUnsafeControls(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint == 0 || (codePoint < 32 && codePoint != '\n' && codePoint != '\r' && codePoint != '\t'));
    }

    private String maskEmail(String value) {
        int at = value.indexOf('@');
        int dot = value.lastIndexOf('.');
        if (at <= 0 || dot <= at + 1 || dot == value.length() - 1) {
            return "[MASKED_EMAIL]";
        }
        return value.charAt(0) + "***@" + value.charAt(at + 1) + "***" + value.substring(dot);
    }

    private String lastAlphanumeric(String value, int count) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[^A-Za-z0-9]", "");
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.substring(Math.max(0, normalized.length() - count));
    }

    private String truncateUtf8(String value, int maximumBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximumBytes) {
            return value;
        }
        int budget = maximumBytes - "…".getBytes(StandardCharsets.UTF_8).length;
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (used + characterBytes > budget) {
                break;
            }
            result.append(character);
            used += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.append('…').toString();
    }

    private int jsonBytes(SanitizedValue value) {
        return value.accept(new SanitizedValue.Visitor<>() {
            @Override
            public Integer string(String text) {
                return jsonStringBytes(text);
            }

            @Override
            public Integer number(BigDecimal number) {
                return number.toPlainString().getBytes(StandardCharsets.UTF_8).length;
            }

            @Override
            public Integer bool(boolean value) {
                return value ? 4 : 5;
            }

            @Override
            public Integer object(Map<String, SanitizedValue> fields) {
                int bytes = 2;
                int index = 0;
                for (Map.Entry<String, SanitizedValue> field : fields.entrySet()) {
                    bytes += (index++ == 0 ? 0 : 1) + jsonStringBytes(field.getKey()) + 1 + jsonBytes(field.getValue());
                }
                return bytes;
            }

            @Override
            public Integer array(List<SanitizedValue> elements) {
                int bytes = 2;
                for (int index = 0; index < elements.size(); index++) {
                    bytes += (index == 0 ? 0 : 1) + jsonBytes(elements.get(index));
                }
                return bytes;
            }

            @Override
            public Integer nil() {
                return 4;
            }
        });
    }

    private int jsonStringBytes(String value) {
        int bytes = 2;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == '"' || codePoint == '\\' || codePoint == '\b' || codePoint == '\f'
                    || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                bytes += 2;
            } else {
                bytes += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            }
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static final class PayloadOversizeException extends RuntimeException {}
}
