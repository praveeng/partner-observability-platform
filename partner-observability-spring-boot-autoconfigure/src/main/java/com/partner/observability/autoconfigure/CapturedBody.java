package com.partner.observability.autoconfigure;

import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.payload.SanitizationResult;

record CapturedBody(SanitizationResult payload, CorrelationIdentifiers identifiers) {}
