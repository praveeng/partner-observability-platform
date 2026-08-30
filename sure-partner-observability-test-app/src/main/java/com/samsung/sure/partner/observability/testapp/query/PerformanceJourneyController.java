package com.samsung.sure.partner.observability.testapp.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.core.query.JourneyIdentifierType;
import com.samsung.sure.partner.observability.core.query.JourneyResolution;
import com.samsung.sure.partner.observability.core.query.JourneyResolver;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only authenticated facade over the generic stateless resolver. */
@RestController
@RequestMapping("/fixture/journey")
@ConditionalOnProperty(name = "local-synthetic.performance-controls-enabled", havingValue = "true")
public final class PerformanceJourneyController {
    private final Credential alpha;
    private final Credential beta;

    public PerformanceJourneyController(
            ObjectMapper objectMapper,
            @Value("${local-synthetic.query.endpoint}") URI queryEndpoint,
            @Value("${local-synthetic.query.alpha-username}") String alphaUsername,
            @Value("${local-synthetic.query.alpha-password}") String alphaPassword,
            @Value("${local-synthetic.query.beta-username}") String betaUsername,
            @Value("${local-synthetic.query.beta-password}") String betaPassword,
            @Value("${local-synthetic.query.service-name:partner-observability-test-app}") String serviceName) {
        alpha = credential("alpha", alphaUsername, alphaPassword,
                new JourneyResolver("synthetic-alpha", new LocalLokiJourneyRecordSource(
                        objectMapper, queryEndpoint, alphaUsername, alphaPassword, serviceName)));
        beta = credential("beta", betaUsername, betaPassword,
                new JourneyResolver("synthetic-beta", new LocalLokiJourneyRecordSource(
                        objectMapper, queryEndpoint, betaUsername, betaPassword, serviceName)));
    }

    @PostMapping
    public ResponseEntity<?> resolve(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Query query) {
        Optional<Credential> authenticated = authenticate(authorization);
        if (authenticated.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("outcome", "SYNTHETIC_QUERY_DENIED"));
        }
        JourneyIdentifierType type;
        try {
            type = type(query.type());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("outcome", "SYNTHETIC_QUERY_INVALID"));
        }
        int days = Math.max(1, Math.min(16, query.fromDaysAgo()));
        Instant to = Instant.now();
        JourneyResolution result = authenticated.get().resolver().resolve(
                "SYNTHETIC_ASYNC", type, query.value(), to.minus(days, ChronoUnit.DAYS), to);
        int identifiers = result.identifiers().values().stream().mapToInt(java.util.List::size).sum();
        return ResponseEntity.ok(Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "partner", authenticated.get().partner(),
                "status", result.status().name(),
                "rounds", result.rounds(),
                "identifiers", identifiers,
                "records", result.records().size(),
                "projectedBytes", result.projectedBytes()));
    }

    private Optional<Credential> authenticate(String header) {
        if (header == null || !header.startsWith("Basic ")) return Optional.empty();
        byte[] supplied;
        try {
            supplied = Base64.getDecoder().decode(header.substring(6));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (MessageDigest.isEqual(alpha.encoded(), supplied)) return Optional.of(alpha);
        if (MessageDigest.isEqual(beta.encoded(), supplied)) return Optional.of(beta);
        return Optional.empty();
    }

    private Credential credential(String partner, String username, String password, JourneyResolver resolver) {
        return new Credential(partner, (username + ":" + password).getBytes(StandardCharsets.UTF_8), resolver);
    }

    private JourneyIdentifierType type(String value) {
        return switch (value) {
            case "applicationId", "journey" -> JourneyIdentifierType.APPLICATION_ID;
            case "loanId" -> JourneyIdentifierType.LOAN_ID;
            case "correlationId" -> JourneyIdentifierType.ORIGINAL_CORRELATION_ID;
            case "partnerReferenceId" -> JourneyIdentifierType.PARTNER_REFERENCE_ID;
            case "callbackReferenceId" -> JourneyIdentifierType.CALLBACK_REFERENCE_ID;
            case "detail" -> JourneyIdentifierType.REQUEST_ID;
            default -> throw new IllegalArgumentException("unsupported query type");
        };
    }

    public record Query(String type, String value, int fromDaysAgo) {}
    private record Credential(String partner, byte[] encoded, JourneyResolver resolver) {}
}
