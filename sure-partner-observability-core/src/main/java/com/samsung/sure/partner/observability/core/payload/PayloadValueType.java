package com.samsung.sure.partner.observability.core.payload;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Expected DTO/field type. Type information narrows capture and can never expand the path allowlist. */
public enum PayloadValueType {
    SAFE_SCALAR,
    STRING,
    NUMBER,
    BOOLEAN;

    boolean accepts(Object value) {
        return switch (this) {
            case SAFE_SCALAR -> value instanceof String || value instanceof Number || value instanceof Boolean;
            case STRING -> value instanceof String;
            case NUMBER -> isSupportedNumber(value);
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    private static boolean isSupportedNumber(Object value) {
        return value instanceof BigDecimal
                || value instanceof BigInteger
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double;
    }
}
