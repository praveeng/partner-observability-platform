package com.partner.observability.autoconfigure;

import com.partner.observability.core.payload.PayloadStatus;

public record UnavailableBody(PayloadStatus status) {}
