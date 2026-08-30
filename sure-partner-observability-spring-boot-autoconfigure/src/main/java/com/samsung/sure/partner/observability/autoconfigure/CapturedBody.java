package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.model.CorrelationIdentifiers;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;

record CapturedBody(SanitizationResult payload, CorrelationIdentifiers identifiers) {}
