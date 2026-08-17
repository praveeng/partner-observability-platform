package com.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TlsInstrumentationStaticSafetyTest {

    private static final List<String> FORBIDDEN_TLS_MUTATIONS = List.of(
            "X509TrustManager",
            "TrustManagerFactory",
            "HostnameVerifier",
            "SSLContext.getInstance",
            ".sslSocketFactory(",
            ".hostnameVerifier(",
            "NoopHostnameVerifier",
            "InsecureTrustManagerFactory",
            "trustAll",
            "replace(\"https://",
            "replaceAll(\"https://",
            "http://");

    @Test
    void productionStarterSourcesContainNoTlsBypassOrDowngradeMechanism() throws IOException {
        Path root = repositoryRoot();
        List<Path> sourceRoots = List.of(
                root.resolve("partner-observability-spring-boot-autoconfigure/src/main/java"),
                root.resolve("partner-observability-spring-boot-starter/src/main/java"));

        for (Path sourceRoot : sourceRoots) {
            if (!Files.exists(sourceRoot)) continue;
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    assertThat(FORBIDDEN_TLS_MUTATIONS)
                            .as("forbidden TLS mutation in %s", root.relativize(file))
                            .noneMatch(source::contains);
                }
            }
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
