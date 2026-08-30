#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

readonly manifest="test/performance/profiles.json"
readonly acceptance="docs/acceptance-criteria.md"

command -v jq >/dev/null 2>&1 || {
  echo 'FAIL performance manifest: jq is required.' >&2
  exit 1
}

[[ -f "$manifest" ]] || {
  echo "FAIL performance manifest: missing $manifest" >&2
  exit 1
}
jq empty "$manifest"
python3 -m unittest discover -s test/performance/helpers -p 'test_*.py'

jq -e '
  .schemaVersion == 2 and
  .definitionStatus == "READY" and
  .releaseMode == "full" and
  .springProfile == "local" and
  .mandatoryProfileCount == 9 and
  .repetitions == 3 and
  (.profiles | length) == .mandatoryProfileCount and
  ([.profiles[].id] | length) == ([.profiles[].id] | unique | length) and
  ([.mandatoryScenarios[].id] | length) == ([.mandatoryScenarios[].id] | unique | length) and
  ([.mandatoryScenarios[].id] - [.profiles[].executions[].mappedScenarios[]] | length) == 0 and
  all(.profiles[];
    .springProfile == "local" and
    (.durationSeconds | type == "number" and . > 0) and
    (.warmupSeconds | type == "number" and . > 0) and
    (.cooldownSeconds | type == "number" and . > 0) and
    ((.arrivalRatePerSecond // .concurrency // .virtualUsers) | type == "number" and . > 0) and
    (.workload | type == "object" and length > 0) and
    (.executions | type == "array" and length > 0) and
    (.thresholds | type == "array" and length > 0) and
    ((.arrivalRatePerSecond == null) or
      (.expectedScheduledOperations == (.arrivalRatePerSecond * .durationSeconds))) and
    ([.thresholds[].id] | length) == ([.thresholds[].id] | unique | length)
  ) and
  ([.profiles[].id] | sort) == ([
    "disabled", "metadata", "full-sanitized", "saturation", "mixed-soak",
    "reactive", "callback-mvc", "callback-webflux", "journey-query"
  ] | sort)
' "$manifest" >/dev/null || {
  echo 'FAIL performance manifest: schema, uniqueness, load, duration, threshold, or scenario coverage validation failed.' >&2
  exit 1
}

jq -e '
  .phases == {defaultWarmupSeconds:180, defaultMeasuredSeconds:900,
              defaultCooldownSeconds:120, fullDurationToleranceSeconds:5} and
  .prerequisites.minimumLogicalCpu == 8 and
  .prerequisites.minimumAvailableMemoryBytes == 12884901888 and
  .environment.testApp == {cpus:2, memoryBytes:2147483648} and
  .environment.reactiveTestApp == {cpus:2, memoryBytes:2147483648} and
  .environment.mockPartner == {cpus:1, memoryBytes:536870912} and
  .environment.alloy == {cpus:1, memoryBytes:536870912} and
  .environment.loki == {cpus:2, memoryBytes:2147483648} and
  .environment.prometheus == {cpus:1, memoryBytes:1073741824} and
  .environment.grafana == {cpus:0.5, memoryBytes:536870912} and
  .jvm.heapMaximumBytes == 1073741824 and .jvm.garbageCollector == "G1GC" and
  .jvm.options == ["-Xms512m", "-Xmx1024m", "-XX:MaxMetaspaceSize=256m",
                   "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError"] and
  .jvm.jfr == {enabled:true, settings:"profile", maxSizeBytes:268435456, dumpOnExit:true} and
  .loadCompleteness.minimumScheduledStartRatio == 0.99 and
  .loadCompleteness.disabledExpectedMeasuredOperations == 900000 and
  .loadCompleteness.disabledMinimumScheduledStarts == 891000 and
  .measurement.cpuSampleSeconds == 5 and .measurement.heapSampleSeconds == 10 and
  .measurement.deadlockCheckSeconds == 300 and .measurement.cpuQuota == 2 and
  .measurement.heapPlateauMaximumXmxFraction == 0.1 and
  .measurement.heapSlopeMaximumXmxFractionPerMinute == 0.005 and
  .measurement.gcPauseP95Milliseconds == 250 and
  .measurement.gcPauseMaximumMilliseconds == 2000 and
  .measurement.gcPauseMaximumMeasuredFraction == 0.02 and
  .measurement.fullGcMaximumCount == 2 and
  .comparison.reuseMaximumAgeSeconds == 3600 and
  .comparison.medianLimitsPercent == {p95Degradation:10, p99Degradation:15,
                                      cpuIncrease:15, peakHeapIncrease:20} and
  .comparison.worstRunMultiplier == 1.25 and
  .comparison.baselineMaximumCoefficientOfVariationPercent ==
    {p95:10, p99:15, normalizedMeanCpu:15, peakHeap:15}
' "$manifest" >/dev/null || {
  echo 'FAIL performance manifest: approved Q015-A environment or measurement values drifted.' >&2
  exit 1
}

while IFS=$'\t' read -r profile_name duration; do
  rg -F "| ${profile_name} |" "$acceptance" >/dev/null || {
    printf 'FAIL performance manifest drift: acceptance profile is not represented in docs: %s\n' "$profile_name" >&2
    exit 1
  }
  if (( duration <= 0 )); then
    printf 'FAIL performance manifest duration: %s has %s seconds\n' "$profile_name" "$duration" >&2
    exit 1
  fi
done < <(jq -r '.profiles[] | [.name, .durationSeconds] | @tsv' "$manifest")

# These are the approved non-null acceptance rows whose values take precedence over Q015-A.
# Exact line checks make a documentation edit fail until the executable manifest is reviewed too.
readonly acceptance_contract_lines=(
  '| Metadata | 1,000 events/s for 30 min, 1 KiB metadata |'
  '| Full sanitized | 250 events/s for 30 min, 32 KiB safe textual candidate |'
  '| Saturation | Backend blackhole, 2,000 attempts/s for 15 min |'
  '| Mixed soak | 80% metadata success, 10% errors, 10% full; 1,000 attempts/s for 60 min |'
  '| Reactive | 500 concurrent streaming/cancelled calls for 30 min |'
  '| Callback MVC | 500 authenticated metadata callbacks/s for 30 min plus 10% async completion |'
  '| Callback WebFlux | 500 concurrent callback bodies with 20% cancellation for 30 min |'
  'The previously unspecified Disabled and Journey query measured durations are 900 seconds.'
)
for contract_line in "${acceptance_contract_lines[@]}"; do
  rg -F "$contract_line" "$acceptance" >/dev/null || {
    printf 'FAIL performance manifest drift: approved acceptance text changed: %s\n' "$contract_line" >&2
    exit 1
  }
done

jq -e '
  (.profiles[] | select(.id == "disabled") | .durationSeconds == 900 and .arrivalRatePerSecond == 1000) and
  (.profiles[] | select(.id == "metadata") | .durationSeconds == 1800 and .arrivalRatePerSecond == 1000) and
  (.profiles[] | select(.id == "full-sanitized") | .durationSeconds == 1800 and .arrivalRatePerSecond == 250 and .workload.payloadBytes == 32768) and
  (.profiles[] | select(.id == "saturation") | .durationSeconds == 900 and .arrivalRatePerSecond == 2000) and
  (.profiles[] | select(.id == "mixed-soak") | .durationSeconds == 3600 and .arrivalRatePerSecond == 1000 and ([.workload.requestMixPercent[]] | add) == 100) and
  (.profiles[] | select(.id == "mixed-soak") | .workload.requestMixPercent ==
    {syncMetadataSuccess:65, asyncJourneyMetadataSuccess:15, partner4xx:4, partner5xx:3,
     timeout:2, networkFailure:1, fullSanitizedLargeJson:5, binaryDocument:5}) and
  (.profiles[] | select(.id == "reactive") | .durationSeconds == 1800 and .concurrency == 500 and
    .workload.httpClientMix == {webClientPercent:100} and
    .workload.streamCreationRatePerSecond == 50 and .workload.elementsPerStream == 32 and
    .workload.elementBytes == 2048 and .workload.streamLifetimeSeconds == 20 and
    .workload.completedPercent == 75 and .workload.cancelledPercent == 25 and
    .workload.cancelAfterSecondsRange == [5,15]) and
  (.profiles[] | select(.id == "callback-mvc") | .durationSeconds == 1800 and
    .arrivalRatePerSecond == 500 and .workload.completionMixPercent ==
    {inline:90, shortDeferred:8, longDeferred:2}) and
  (.profiles[] | select(.id == "callback-webflux") | .durationSeconds == 1800 and
    .concurrency == 500 and .workload.cancelledPercent == 20 and
    .workload.completionMixPercent == {inline:90, shortDeferred:8, longDeferred:2}) and
  (.profiles[] | select(.id == "journey-query") | .durationSeconds == 900 and
    .concurrency == 10 and .virtualUsers == 10 and .workload.retainedRecords == 500000 and
    .workload.retentionDays == 16 and .workload.minimumTenants == 2 and
    .workload.sameIdentifierCollisionPercent == 10 and .workload.thinkTimeMilliseconds == 750 and
    .workload.minimumSuccessfulQueries == 5000 and
    .workload.recordAgeMixPercent == {last24Hours:50, days1To8:30, days8To16:20} and
    .workload.queryMixPercent == {applicationId:30, loanId:20, correlationId:15,
      partnerReferenceId:10, callbackReferenceId:10, unifiedJourney:10, detail:5})
' "$manifest" >/dev/null || {
  echo 'FAIL performance manifest: an approved non-null acceptance duration/load or Q015-A conflict resolution drifted.' >&2
  exit 1
}

[[ "$(rg -c 'partner-observability-reactive-test-app' alloy/local-config.alloy)" -eq 2 ]] || {
  echo 'FAIL performance manifest: the two fixed partner pipelines must allow the reactive test service.' >&2
  exit 1
}

if find . -path '*/src/main/resources/application*.yml' -o -path '*/src/main/resources/application*.yaml' | grep -q .; then
  echo 'FAIL performance profile: Spring application YAML is prohibited.' >&2
  exit 1
fi

echo 'PASS: nine full-duration profiles and all P01-P24 mapped coverage cases are machine-readable and consistent.'
