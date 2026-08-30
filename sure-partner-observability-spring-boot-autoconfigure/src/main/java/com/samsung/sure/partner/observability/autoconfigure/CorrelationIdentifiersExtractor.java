package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.model.CorrelationIdentifiers;
import java.util.Optional;

/**
 * Typed application extension point. Implementations inspect only objects already decoded by the
 * business integration and must never use an identifier to select a partner.
 */
@FunctionalInterface
public interface CorrelationIdentifiersExtractor {
    Optional<CorrelationIdentifiers> extract(String configuredApiName, ObservationLeg leg, Object decodedBody);
}
