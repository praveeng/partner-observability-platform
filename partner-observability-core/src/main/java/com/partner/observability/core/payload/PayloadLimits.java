package com.partner.observability.core.payload;

public record PayloadLimits(
        int rawCandidateBytes,
        int safePayloadBytes,
        int stringBytes,
        int objectDepth,
        int totalNodes,
        int arrayElements) {

    public static final int HARD_MAX_RAW_CANDIDATE_BYTES = 64 * 1024;
    public static final int HARD_MAX_SAFE_PAYLOAD_BYTES = 32 * 1024;
    public static final int HARD_MAX_STRING_BYTES = 2048;
    public static final int HARD_MAX_OBJECT_DEPTH = 8;
    public static final int HARD_MAX_TOTAL_NODES = 128;
    public static final int HARD_MAX_ARRAY_ELEMENTS = 64;

    public PayloadLimits {
        requireRange(rawCandidateBytes, 1, HARD_MAX_RAW_CANDIDATE_BYTES, "rawCandidateBytes");
        requireRange(safePayloadBytes, 1, HARD_MAX_SAFE_PAYLOAD_BYTES, "safePayloadBytes");
        requireRange(stringBytes, 1, HARD_MAX_STRING_BYTES, "stringBytes");
        requireRange(objectDepth, 1, HARD_MAX_OBJECT_DEPTH, "objectDepth");
        requireRange(totalNodes, 1, HARD_MAX_TOTAL_NODES, "totalNodes");
        requireRange(arrayElements, 1, HARD_MAX_ARRAY_ELEMENTS, "arrayElements");
    }

    public static PayloadLimits defaults() {
        return new PayloadLimits(
                HARD_MAX_RAW_CANDIDATE_BYTES,
                HARD_MAX_SAFE_PAYLOAD_BYTES,
                HARD_MAX_STRING_BYTES,
                HARD_MAX_OBJECT_DEPTH,
                HARD_MAX_TOTAL_NODES,
                HARD_MAX_ARRAY_ELEMENTS);
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
