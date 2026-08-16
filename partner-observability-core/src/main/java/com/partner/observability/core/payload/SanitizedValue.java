package com.partner.observability.core.payload;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A closed safe-tree type with deliberately no binary, arbitrary-object, or throwable subtype. */
public sealed interface SanitizedValue
        permits SanitizedStringValue,
                SanitizedNumberValue,
                SanitizedBooleanValue,
                SanitizedObjectValue,
                SanitizedArrayValue,
                SanitizedNullValue {

    Kind kind();

    <T> T accept(Visitor<T> visitor);

    default Object toJavaValue() {
        return accept(new Visitor<>() {
            @Override
            public Object string(String value) {
                return value;
            }

            @Override
            public Object number(BigDecimal value) {
                return value;
            }

            @Override
            public Object bool(boolean value) {
                return value;
            }

            @Override
            public Object object(Map<String, SanitizedValue> fields) {
                Map<String, Object> copy = new LinkedHashMap<>();
                fields.forEach((key, value) -> copy.put(key, value.toJavaValue()));
                return Collections.unmodifiableMap(copy);
            }

            @Override
            public Object array(List<SanitizedValue> elements) {
                List<Object> copy = new ArrayList<>(elements.size());
                elements.forEach(value -> copy.add(value.toJavaValue()));
                return Collections.unmodifiableList(copy);
            }

            @Override
            public Object nil() {
                return null;
            }
        });
    }

    enum Kind {
        STRING,
        NUMBER,
        BOOLEAN,
        OBJECT,
        ARRAY,
        NULL
    }

    interface Visitor<T> {
        T string(String value);

        T number(BigDecimal value);

        T bool(boolean value);

        T object(Map<String, SanitizedValue> fields);

        T array(List<SanitizedValue> elements);

        T nil();
    }
}

record SanitizedStringValue(String value) implements SanitizedValue {
    @Override
    public Kind kind() {
        return Kind.STRING;
    }

    @Override
    public <T> T accept(Visitor<T> visitor) {
        return visitor.string(value);
    }
}

record SanitizedNumberValue(BigDecimal value) implements SanitizedValue {
    @Override
    public Kind kind() {
        return Kind.NUMBER;
    }

    @Override
    public <T> T accept(Visitor<T> visitor) {
        return visitor.number(value);
    }
}

record SanitizedBooleanValue(boolean value) implements SanitizedValue {
    @Override
    public Kind kind() {
        return Kind.BOOLEAN;
    }

    @Override
    public <T> T accept(Visitor<T> visitor) {
        return visitor.bool(value);
    }
}

final class SanitizedObjectValue implements SanitizedValue {
    private final Map<String, SanitizedValue> fields;

    SanitizedObjectValue(Map<String, SanitizedValue> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    @Override
    public Kind kind() {
        return Kind.OBJECT;
    }

    @Override
    public <T> T accept(Visitor<T> visitor) {
        return visitor.object(fields);
    }
}

final class SanitizedArrayValue implements SanitizedValue {
    private final List<SanitizedValue> elements;

    SanitizedArrayValue(List<SanitizedValue> elements) {
        this.elements = List.copyOf(elements);
    }

    @Override
    public Kind kind() {
        return Kind.ARRAY;
    }

    @Override
    public <T> T accept(Visitor<T> visitor) {
        return visitor.array(elements);
    }
}

enum SanitizedNullValue implements SanitizedValue {
    INSTANCE;

    @Override
    public Kind kind() {
        return Kind.NULL;
    }

    @Override
    public <T> T accept(Visitor<T> visitor) {
        return visitor.nil();
    }
}
