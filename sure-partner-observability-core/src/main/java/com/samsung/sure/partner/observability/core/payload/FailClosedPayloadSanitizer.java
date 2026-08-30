package com.samsung.sure.partner.observability.core.payload;

import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, allowlist-first sanitizer. It never reflects, serializes, or calls arbitrary toString. */
public final class FailClosedPayloadSanitizer implements PayloadSanitizer {

    private static final Set<String> REMOVAL_ALIASES = Set.of(
            "authorization", "proxyauthorization", "auth", "credential", "credentials",
            "authorizationheader", "authorizationvalue", "authorizationtoken", "proxyauthorizationheader",
            "xauthorization", "authentication", "password", "passwordvalue", "passcode", "secret",
            "secretvalue", "clientsecret", "clientsecretvalue", "token", "accesstoken",
            "refreshtoken", "bearertoken", "oauthtoken", "jwt", "cookie", "cookieheader",
            "setcookie", "setcookieheader", "session", "sessionid", "sessioncookie", "csrftoken",
            "xsrftoken", "apikey", "apikeyvalue", "xapikey", "secretkey", "privatekey", "signingkey",
            "encryptionkey", "encryptionkeyvalue", "encryptioniv", "initializationvector", "iv",
            "ivvalue", "nonce", "otp", "otpcode", "onetimepassword", "onetimecode",
            "verificationcode", "authenticationpin", "pin",
            "card", "cardnumber", "cardno", "pan", "pannumber", "cvv", "cvv2", "cvc", "cid",
            "trackdata", "magneticstripe", "cardexpiry", "cardexpiration");
    private static final Set<String> PHONE_ALIASES = Set.of(
            "phone", "phonenumber", "telephone", "telephonenumber", "mobile", "mobilephone",
            "contactnumber");
    private static final Set<String> EMAIL_ALIASES = Set.of("email", "emailaddress", "emailid");
    private static final Set<String> ACCOUNT_ALIASES = Set.of(
            "account", "accountnumber", "acctno", "bankaccount", "bankaccountnumber", "iban");
    private static final Set<String> NATIONAL_ID_ALIASES = Set.of(
            "nationalid", "nationalidentifier", "governmentid", "taxid", "taxidentifier", "ssn",
            "socialsecuritynumber", "passportnumber");
    private static final Set<String> ADDRESS_ALIASES = Set.of(
            "address", "postaladdress", "streetaddress", "homeaddress", "mailingaddress",
            "residentialaddress", "billingaddress");
    private static final Set<String> DOCUMENT_ALIASES = Set.of(
            "document", "documents", "attachment", "attachments", "signature", "pdf", "image", "photo");

    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    private final PayloadLimits limits;
    private final BinaryDigestMode digestMode;

    public FailClosedPayloadSanitizer() {
        this(PayloadLimits.defaults(), BinaryDigestMode.DISABLED);
    }

    public FailClosedPayloadSanitizer(PayloadLimits limits) {
        this(limits, BinaryDigestMode.DISABLED);
    }

    public FailClosedPayloadSanitizer(PayloadLimits limits, BinaryDigestMode digestMode) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.digestMode = java.util.Objects.requireNonNull(digestMode, "digestMode");
    }

    @Override
    public SanitizationResult sanitize(PayloadInput input, PayloadSchema schema, PayloadCaptureMode mode) {
        try {
            java.util.Objects.requireNonNull(input, "input");
            java.util.Objects.requireNonNull(schema, "schema");
            java.util.Objects.requireNonNull(mode, "mode");
            if (mode == PayloadCaptureMode.NO_PAYLOAD || mode == PayloadCaptureMode.METADATA_ONLY) {
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
            if (input.value() instanceof String text && utf8LengthExceeds(text, limits.rawCandidateBytes())) {
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

    /** Sanitizes a registered DTO projection without reflection or whole-object serialization. */
    @Override
    public <T> SanitizationResult sanitizeObject(
            T source, PayloadObjectSchema<T> schema, PayloadCaptureMode mode) {
        try {
            java.util.Objects.requireNonNull(schema, "schema");
            java.util.Objects.requireNonNull(mode, "mode");
            if (mode == PayloadCaptureMode.NO_PAYLOAD || mode == PayloadCaptureMode.METADATA_ONLY) {
                return SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);
            }
            return sanitize(PayloadInput.of(schema.project(source)), schema.payloadSchema(), mode);
        } catch (RuntimeException exception) {
            return SanitizationResult.rejected(PayloadStatus.MALFORMED);
        }
    }

    private SanitizationResult binaryOmission(BinaryKind kind, PayloadInput input) {
        PayloadStatus status = statusFor(kind);
        OmittedBinaryMetadata metadata = new OmittedBinaryMetadata(
                kind, 1, input.declaredSizeBytes(), input.contentType(), safeDigest(input.value()));
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
        private int fieldEntries;
        private int removed;
        private int masked;
        private int omitted;
        private int binaryCount;
        private BinaryKind binaryKind;
        private Optional<String> binarySha256 = Optional.empty();

        private Traversal(PayloadSchema schema, PayloadInput input) {
            this.schema = schema;
            this.input = input;
        }

        private Optional<SanitizedValue> visit(
                Object candidate, String path, int depth, PayloadSchema.FieldRule inheritedRule) {
            if (++nodes > limits.totalNodes()) {
                throw new PayloadOversizeException();
            }
            if (depth > limits.objectDepth()) {
                omitted++;
                return Optional.empty();
            }
            if (candidate == null) {
                Optional<PayloadSchema.FieldRule> nullRule = rule(path, inheritedRule);
                if (nullRule.map(PayloadSchema.FieldRule::policy).orElse(null) == PayloadFieldPolicy.REMOVE) {
                    removed++;
                    return Optional.empty();
                }
                return nullRule.map(ignored -> SanitizedNullValue.INSTANCE);
            }
            PayloadSchema.FieldRule fieldRule = rule(path, inheritedRule).orElse(null);
            if (fieldRule != null && fieldRule.policy() == PayloadFieldPolicy.REMOVE) {
                removed++;
                return Optional.empty();
            }
            if (isBinaryType(candidate)) {
                recordBinary(binaryKind(candidate), candidate);
                return Optional.empty();
            }
            if (candidate instanceof Map<?, ?> map) {
                return visitMap(map, path, depth);
            }
            if (candidate instanceof List<?> list) {
                return visitList(list, path, depth, fieldRule);
            }
            if (fieldRule == null) {
                omitted++;
                return Optional.empty();
            }
            return visitScalar(candidate, fieldRule);
        }

        private Optional<SanitizedValue> visitMap(Map<?, ?> map, String path, int depth) {
            enter(map);
            try {
                Map<String, SanitizedValue> safe = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (++fieldEntries > limits.totalNodes()) {
                        throw new PayloadOversizeException();
                    }
                    if (!(entry.getKey() instanceof String rawKey)) {
                        omitted++;
                        continue;
                    }
                    if (rawKey.length() > 256) {
                        omitted++;
                        continue;
                    }
                    String key = PayloadSchema.normalizeKey(rawKey);
                    if (key.isEmpty()) {
                        omitted++;
                        continue;
                    }
                    if (isRemovalAlias(key)) {
                        removed++;
                        continue;
                    }
                    PayloadFieldPolicy configuredNamePolicy = schema.policyForFieldName(key).orElse(null);
                    if (configuredNamePolicy == PayloadFieldPolicy.REMOVE) {
                        removed++;
                        continue;
                    }
                    String childPath = path.isEmpty() ? key : path + "." + key;
                    boolean known = schema.policyFor(childPath).isPresent() || schema.hasDescendant(childPath);
                    if (!known) {
                        omitted++;
                        continue;
                    }
                    if (schema.policyFor(childPath).orElse(null) == PayloadFieldPolicy.REMOVE) {
                        removed++;
                        continue;
                    }
                    BinaryKind keyKind = documentKind(key);
                    if (keyKind != null) {
                        recordBinary(keyKind, entry.getValue());
                        continue;
                    }
                    PayloadFieldPolicy builtInMask = builtInMask(key);
                    PayloadFieldPolicy effectiveNamePolicy = builtInMask != null ? builtInMask : configuredNamePolicy;
                    if (effectiveNamePolicy != null) {
                        safe.put(key, mask(entry.getValue(), effectiveNamePolicy));
                        masked++;
                        continue;
                    }
                    visit(entry.getValue(), childPath, depth + 1, schema.ruleFor(childPath).orElse(null))
                            .ifPresent(value -> safe.put(key, value));
                }
                return safe.isEmpty() ? Optional.empty() : Optional.of(new SanitizedObjectValue(safe));
            } finally {
                leave(map);
            }
        }

        private Optional<SanitizedValue> visitList(
                List<?> list, String path, int depth, PayloadSchema.FieldRule inheritedRule) {
            if (list.size() > limits.arrayElements()) {
                throw new PayloadOversizeException();
            }
            enter(list);
            try {
                List<SanitizedValue> safe = new ArrayList<>();
                String elementPath = path + "[]";
                PayloadSchema.FieldRule elementRule = schema.ruleFor(elementPath)
                        .orElse(schema.ruleFor(path).orElse(inheritedRule));
                for (Object value : list) {
                    visit(value, elementPath, depth + 1, elementRule).ifPresent(safe::add);
                }
                return Optional.of(new SanitizedArrayValue(safe));
            } finally {
                leave(list);
            }
        }

        private Optional<SanitizedValue> visitScalar(Object value, PayloadSchema.FieldRule rule) {
            PayloadFieldPolicy policy = rule.policy();
            if (policy != PayloadFieldPolicy.ALLOW) {
                masked++;
                return Optional.of(mask(value, policy));
            }
            if (!rule.expectedType().accepts(value)) {
                omitted++;
                return Optional.empty();
            }
            if (value instanceof String text) {
                if (isSecretValue(text)) {
                    removed++;
                    return Optional.empty();
                }
                BinaryKind encoded = encodedKind(text);
                if (encoded != null) {
                    recordBinary(encoded, null);
                    return Optional.empty();
                }
                if (utf8LengthExceeds(text, limits.rawCandidateBytes())) {
                    throw new PayloadOversizeException();
                }
                if (containsUnsafeControls(text)) {
                    omitted++;
                    return Optional.empty();
                }
                return Optional.of(new SanitizedStringValue(truncateUtf8(text, limits.stringBytes())));
            }
            if (value instanceof Boolean bool) {
                return Optional.of(new SanitizedBooleanValue(bool));
            }
            if (value instanceof BigDecimal decimal) {
                return safeDecimal(decimal);
            }
            if (value instanceof BigInteger integer) {
                if (integer.bitLength() > 512) {
                    omitted++;
                    return Optional.empty();
                }
                return safeDecimal(new BigDecimal(integer));
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return safeDecimal(BigDecimal.valueOf(((Number) value).longValue()));
            }
            if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (Double.isFinite(number)) {
                    return safeDecimal(BigDecimal.valueOf(number));
                }
            }
            omitted++;
            return Optional.empty();
        }

        private Optional<SanitizedValue> safeDecimal(BigDecimal value) {
            if (value.precision() > 128 || value.scale() < -128 || value.scale() > 128) {
                omitted++;
                return Optional.empty();
            }
            return Optional.of(new SanitizedNumberValue(value));
        }

        private Optional<PayloadSchema.FieldRule> rule(String path, PayloadSchema.FieldRule fallback) {
            Optional<PayloadSchema.FieldRule> configured = schema.ruleFor(path);
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

        private void recordBinary(BinaryKind kind, Object candidate) {
            binaryCount++;
            omitted++;
            if (binaryKind == null) {
                binaryKind = kind;
            }
            if (binaryCount == 1) {
                binarySha256 = safeDigest(candidate);
            } else {
                binarySha256 = Optional.empty();
            }
        }

        private OmittedBinaryMetadata binaryMetadata() {
            return new OmittedBinaryMetadata(
                    binaryKind,
                    Math.min(binaryCount, PayloadLimits.HARD_MAX_TOTAL_NODES),
                    input.declaredSizeBytes(),
                    input.contentType(),
                    binarySha256);
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
        int start = trimStart(value);
        int end = trimEnd(value, start);
        int length = end - start;
        if (startsWithIgnoreCase(value, start, end, "bearer ")
                || startsWithIgnoreCase(value, start, end, "basic ")
                || startsWithIgnoreCase(value, start, end, "-----begin ")) {
            return true;
        }
        if (length <= 4096) {
            java.util.regex.Matcher matcher = JWT.matcher(value);
            matcher.region(start, end);
            if (matcher.matches()) {
                return true;
            }
        }
        return isPaymentCard(value, start, end) || isKnownSecretToken(value, start, end);
    }

    private BinaryKind encodedKind(String value) {
        int start = trimStart(value);
        int end = trimEnd(value, start);
        int length = end - start;
        if (startsWithIgnoreCase(value, start, end, "data:")) {
            return BinaryKind.BASE64;
        }
        if (startsWith(value, start, end, "JVBER")
                || startsWith(value, start, end, "/9j/")
                || startsWith(value, start, end, "iVBORw0KGgo")) {
            return BinaryKind.BASE64;
        }
        if (length < 16) {
            return null;
        }

        int inspectedEnd = Math.min(end, start + limits.rawCandidateBytes());
        boolean standard = true;
        boolean urlSafe = true;
        boolean paddingSeen = false;
        int padding = 0;
        int encodedCharacters = 0;
        for (int index = start; index < inspectedEnd; index++) {
            char character = value.charAt(index);
            if (isBase64AlphaNumeric(character)) {
                if (paddingSeen) {
                    return null;
                }
                encodedCharacters++;
                continue;
            }
            if (character == '+' || character == '/') {
                urlSafe = false;
                if (paddingSeen) {
                    return null;
                }
                encodedCharacters++;
                continue;
            }
            if (character == '-' || character == '_') {
                standard = false;
                if (paddingSeen) {
                    return null;
                }
                encodedCharacters++;
                continue;
            }
            if (character == '=') {
                paddingSeen = true;
                padding++;
                encodedCharacters++;
                if (padding > 2) {
                    return null;
                }
                continue;
            }
            if (character == '\r' || character == '\n') {
                continue;
            }
            return null;
        }

        if (end > inspectedEnd) {
            if ((standard || urlSafe) && encodedCharacters >= 32) {
                return BinaryKind.UNKNOWN_ENCODED;
            }
            return null;
        }
        if (padding > 0) {
            return (standard || urlSafe) && encodedCharacters >= 16 && encodedCharacters % 4 == 0
                    ? BinaryKind.BASE64
                    : null;
        }
        if ((standard || urlSafe) && encodedCharacters >= 32 && encodedCharacters % 4 != 1) {
            return BinaryKind.UNKNOWN_ENCODED;
        }
        return null;
    }

    private boolean isBase64AlphaNumeric(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9';
    }

    private int trimStart(String value) {
        int index = 0;
        int inspectionLimit = Math.min(value.length(), limits.rawCandidateBytes() + 1);
        while (index < inspectionLimit && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private int trimEnd(String value, int start) {
        int index = value.length();
        int inspectionLimit = Math.max(start, value.length() - limits.rawCandidateBytes() - 1);
        while (index > inspectionLimit && Character.isWhitespace(value.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private boolean startsWithIgnoreCase(String value, int start, int end, String prefix) {
        return end - start >= prefix.length() && value.regionMatches(true, start, prefix, 0, prefix.length());
    }

    private boolean startsWith(String value, int start, int end, String prefix) {
        return end - start >= prefix.length() && value.regionMatches(start, prefix, 0, prefix.length());
    }

    private boolean isPaymentCard(String value, int start, int end) {
        if (end - start < 13 || end - start > 37) {
            return false;
        }
        int digits = 0;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') {
                digits++;
            } else if (character != ' ' && character != '-') {
                return false;
            }
        }
        if (digits < 13 || digits > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = end - 1; index >= start; index--) {
            char character = value.charAt(index);
            if (character == ' ' || character == '-') {
                continue;
            }
            int digit = character - '0';
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

    private boolean isKnownSecretToken(String value, int start, int end) {
        int length = end - start;
        if (length == 20 && (value.regionMatches(start, "AKIA", 0, 4)
                || value.regionMatches(start, "ASIA", 0, 4))) {
            for (int index = start + 4; index < end; index++) {
                char character = value.charAt(index);
                if (!((character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9'))) {
                    return false;
                }
            }
            return true;
        }
        return length >= 16 && (startsWithIgnoreCase(value, start, end, "sk_live_")
                || startsWithIgnoreCase(value, start, end, "sk_test_")
                || startsWithIgnoreCase(value, start, end, "xoxb-")
                || startsWithIgnoreCase(value, start, end, "xoxa-"))
                || length >= 24 && (startsWithIgnoreCase(value, start, end, "ghp_")
                        || startsWithIgnoreCase(value, start, end, "gho_")
                        || startsWithIgnoreCase(value, start, end, "ghu_")
                        || startsWithIgnoreCase(value, start, end, "ghs_"));
    }

    private boolean containsUnsafeControls(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint == 0 || (codePoint < 32 && codePoint != '\n' && codePoint != '\r' && codePoint != '\t'));
    }

    private String maskEmail(String value) {
        if (value.length() > limits.rawCandidateBytes()) {
            return "[MASKED_EMAIL]";
        }
        int at = value.indexOf('@');
        int dot = value.lastIndexOf('.');
        int topLevelLength = value.length() - dot - 1;
        if (at <= 0 || dot <= at + 1 || topLevelLength < 1 || topLevelLength > 16
                || !isSafeMaskCharacter(value.charAt(0))
                || !isSafeMaskCharacter(value.charAt(at + 1))) {
            return "[MASKED_EMAIL]";
        }
        for (int index = dot + 1; index < value.length(); index++) {
            if (!isSafeMaskCharacter(value.charAt(index))) {
                return "[MASKED_EMAIL]";
            }
        }
        return value.charAt(0) + "***@" + value.charAt(at + 1) + "***" + value.substring(dot);
    }

    private String lastAlphanumeric(String value, int count) {
        StringBuilder reversed = new StringBuilder(count);
        int inspectionLimit = Math.max(0, value.length() - limits.rawCandidateBytes());
        for (int index = value.length() - 1;
                index >= inspectionLimit && reversed.length() < count;
                index--) {
            char character = value.charAt(index);
            if (isSafeMaskCharacter(character)) {
                reversed.append(character);
            }
        }
        return reversed.reverse().toString();
    }

    private boolean isSafeMaskCharacter(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9';
    }

    private String truncateUtf8(String value, int maximumBytes) {
        if (!utf8LengthExceeds(value, maximumBytes)) {
            return value;
        }
        int budget = maximumBytes - 3;
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int characterBytes = utf8Bytes(codePoint);
            if (used + characterBytes > budget) {
                break;
            }
            result.appendCodePoint(codePoint);
            used += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.append('…').toString();
    }

    private boolean utf8LengthExceeds(String value, int maximumBytes) {
        int bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            bytes += utf8Bytes(codePoint);
            if (bytes > maximumBytes) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        return codePoint <= 0xffff ? 3 : 4;
    }

    private Optional<String> safeDigest(Object candidate) {
        if (digestMode != BinaryDigestMode.SAFE_SHA_256) {
            return Optional.empty();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (candidate instanceof byte[] bytes) {
                if (bytes.length > limits.rawCandidateBytes()) {
                    return Optional.empty();
                }
                digest.update(bytes);
            } else if (candidate instanceof ByteBuffer buffer) {
                if (buffer.remaining() > limits.rawCandidateBytes()) {
                    return Optional.empty();
                }
                digest.update(buffer.asReadOnlyBuffer());
            } else {
                return Optional.empty();
            }
            return Optional.of(java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            return Optional.empty();
        }
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
                bytes += utf8Bytes(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static final class PayloadOversizeException extends RuntimeException {}
}
