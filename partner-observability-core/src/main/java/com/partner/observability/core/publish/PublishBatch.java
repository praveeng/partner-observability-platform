package com.partner.observability.core.publish;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.dispatch.DispatcherConfig;
import com.partner.observability.core.dispatch.TelemetrySubmission;
import java.util.List;
import java.util.Objects;

/** Immutable single-partner batch. The publisher cannot choose a different routing identity. */
public record PublishBatch(PartnerContext partnerContext, List<TelemetrySubmission> submissions, int totalBytes) {

    public PublishBatch {
        Objects.requireNonNull(partnerContext, "partnerContext");
        submissions = List.copyOf(submissions);
        if (submissions.isEmpty() || submissions.size() > DispatcherConfig.HARD_MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("publish batch event count is invalid");
        }
        int calculated = submissions.stream().mapToInt(TelemetrySubmission::serializedSizeBytes).sum();
        if (calculated != totalBytes || totalBytes > DispatcherConfig.HARD_MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("publish batch byte count is invalid");
        }
        boolean mixedPartner = submissions.stream().anyMatch(submission -> !submission
                .envelope()
                .partnerContext()
                .routingKey()
                .equals(partnerContext.routingKey()));
        if (mixedPartner) {
            throw new IllegalArgumentException("publish batch cannot mix partners");
        }
    }
}
