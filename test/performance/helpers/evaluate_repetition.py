#!/usr/bin/env python3
"""Build the machine-readable verdict for one B003 profile repetition."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import re
import shutil
import statistics
import subprocess
from datetime import datetime, timezone


MIB = 1024 * 1024


def load(path: str, default=None):
    if not path:
        return default
    candidate = pathlib.Path(path)
    if not candidate.exists():
        return default
    with candidate.open(encoding="utf-8") as source:
        return json.load(source)


def samples(path: str) -> list[dict]:
    values = []
    candidate = pathlib.Path(path)
    if not candidate.exists():
        return values
    with candidate.open(encoding="utf-8") as source:
        for line in source:
            try:
                values.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return values


def metric(summary: dict, name: str, field: str, default=0):
    return summary.get("metrics", {}).get(name, {}).get("values", {}).get(field, default)


def percentile(values: list[float], quantile: float):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * quantile) - 1))
    return ordered[index]


def cpu_percent(value: str | None, quota: float) -> float | None:
    if not value:
        return None
    try:
        return float(value.rstrip("%")) / quota
    except ValueError:
        return None


def slope_per_minute(points: list[tuple[float, float]]) -> float | None:
    if len(points) < 2:
        return None
    x_mean = statistics.fmean(point[0] for point in points)
    y_mean = statistics.fmean(point[1] for point in points)
    denominator = sum((point[0] - x_mean) ** 2 for point in points)
    if denominator == 0:
        return 0.0
    per_second = sum((x - x_mean) * (y - y_mean) for x, y in points) / denominator
    return per_second * 60.0


def gc_metrics(measured: list[dict], sample_key: str) -> dict:
    snapshots = [value.get(sample_key) for value in measured if value.get(sample_key)]
    if len(snapshots) < 2:
        return {"measured": False}
    first, last = snapshots[0], snapshots[-1]
    counts: dict[str, float] = {}
    buckets: dict[float, float] = {}
    total_sum = 0.0
    full_count = 0.0
    for key, end_value in last.items():
        delta = max(0.0, end_value - first.get(key, 0.0))
        if key.startswith("jvm_gc_pause_seconds_sum"):
            total_sum += delta
        elif key.startswith("jvm_gc_pause_seconds_count"):
            counts[key] = delta
            if 'action="end of major GC"' in key:
                full_count += delta
        elif key.startswith("jvm_gc_pause_seconds_bucket"):
            match = re.search(r'le="([+A-Za-z0-9.eE-]+)"', key)
            if match and match.group(1) != "+Inf":
                boundary = float(match.group(1))
                buckets[boundary] = buckets.get(boundary, 0.0) + delta
    count = sum(counts.values())
    p95 = 0.0
    if count:
        target = count * 0.95
        p95 = next((boundary * 1000.0 for boundary in sorted(buckets)
                    if buckets[boundary] >= target), None)
    boundaries_within_two = [boundary for boundary in buckets if boundary <= 2.0]
    within_two_boundary = max(boundaries_within_two) if boundaries_within_two else None
    within_two = buckets.get(within_two_boundary) if within_two_boundary is not None else None
    maximum_bound = 0.0 if count == 0 else (within_two_boundary * 1000.0
        if within_two is not None and within_two >= count else None)
    return {"measured": True, "count": count, "pauseTotalMilliseconds": total_sum * 1000.0,
            "pauseP95Milliseconds": p95, "pauseMaximumUpperBoundMilliseconds": maximum_bound,
            "fullGcCount": full_count}


def allocation_samples(lines, started_at: datetime, ended_at: datetime) -> tuple[int, int]:
    """Sum JFR allocation-sample weights in the measured interval without retaining stack traces."""
    current_time = None
    total_bytes = 0
    sample_count = 0
    for line in lines:
        start_match = re.search(r'"startTime"\s*:\s*"([^"]+)"', line)
        if start_match:
            try:
                current_time = datetime.fromisoformat(start_match.group(1).replace("Z", "+00:00"))
            except ValueError:
                current_time = None
            continue
        weight_match = re.search(r'"weight"\s*:\s*([0-9]+)', line)
        if weight_match and current_time is not None:
            if started_at <= current_time <= ended_at:
                total_bytes += int(weight_match.group(1))
                sample_count += 1
            current_time = None
    return total_bytes, sample_count


def jfr_allocation_metrics(
        path: str,
        started_at: str,
        ended_at: str,
        successful_operations: int,
        measured_seconds: float) -> dict:
    jfr_tool = shutil.which("jfr")
    candidate = pathlib.Path(path)
    if not jfr_tool or not candidate.is_file() or candidate.stat().st_size == 0:
        return {"measured": False, "reason": "JFR_TOOL_OR_RECORDING_UNAVAILABLE"}
    started = datetime.fromisoformat(started_at.replace("Z", "+00:00"))
    ended = datetime.fromisoformat(ended_at.replace("Z", "+00:00"))
    process = subprocess.Popen(
        [jfr_tool, "print", "--json", "--events", "jdk.ObjectAllocationSample", str(candidate)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert process.stdout is not None
    total_bytes, sample_count = allocation_samples(process.stdout, started, ended)
    stderr = process.stderr.read() if process.stderr is not None else ""
    status = process.wait()
    if status != 0:
        return {"measured": False, "reason": "JFR_ALLOCATION_EXTRACTION_FAILED",
                "diagnostic": stderr.strip()[:256]}
    return {
        "measured": True,
        "method": "JFR_OBJECT_ALLOCATION_SAMPLE_WEIGHT",
        "sampleCount": sample_count,
        "totalAllocatedBytesEstimate": total_bytes,
        "allocatedBytesPerSuccessfulOperation": (
            total_bytes / successful_operations if successful_operations > 0 else None),
        "allocationRateBytesPerSecond": (
            total_bytes / measured_seconds if measured_seconds > 0 else None),
    }


def threshold(result: list[dict], threshold_id: str, actual, operator: str, expected, measured=True):
    if not measured or actual is None:
        passed = False
        reason = "MANDATORY_METRIC_NOT_MEASURED"
    elif operator == "<=":
        passed = actual <= expected
        reason = None if passed else f"THRESHOLD_EXCEEDED:{threshold_id}"
    elif operator == "==":
        passed = actual == expected
        reason = None if passed else f"THRESHOLD_MISMATCH:{threshold_id}"
    else:
        raise ValueError(operator)
    result.append({"id": threshold_id, "operator": operator, "expected": expected,
                   "actual": actual, "measured": measured, "passed": passed})
    return reason


def main() -> None:
    parser = argparse.ArgumentParser()
    for name in ("manifest", "profile-id", "summary", "samples", "timings", "health-before",
                 "health-after", "records", "callbacks-before", "callbacks", "reactive-before",
                 "reactive", "containers", "environment", "jfr-file", "output",
                 "run-id", "git-commit", "started-at", "ended-at", "execution-id", "workload-hash"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--repetition", type=int, required=True)
    parser.add_argument("--actual-duration", type=float, required=True)
    parser.add_argument("--mode", choices=("full", "smoke"), required=True)
    parser.add_argument("--baseline-result", default="")
    parser.add_argument("--diagnostics", default="")
    parser.add_argument("--outage", default="")
    parser.add_argument("--baseline", action="store_true")
    args = parser.parse_args()

    manifest = load(args.manifest)
    profile = next(value for value in manifest["profiles"] if value["id"] == args.profile_id)
    summary = load(args.summary, {})
    timing = load(args.timings, {}).get("operations", {})
    health_before = load(args.health_before, {})
    health_after = load(args.health_after, {})
    records = load(args.records, {})
    callbacks_before = load(args.callbacks_before, {})
    callbacks = load(args.callbacks, {})
    reactive_before = load(args.reactive_before, {})
    reactive = load(args.reactive, {})
    containers = load(args.containers, {})
    diagnostics = load(args.diagnostics, {})
    outage = load(args.outage, [])
    baseline = load(args.baseline_result, {})
    environment = load(args.environment, {})
    environment_fingerprint = hashlib.sha256(json.dumps({
        key: environment.get(key) for key in (
            "operatingSystem", "logicalCpu", "availableMemoryBytes", "dockerLogicalCpu",
            "dockerMemoryBytes", "projectVersion", "jvmOptions", "containerImages",
            "deterministicWorkloadSeed")
    }, sort_keys=True).encode("utf-8")).hexdigest()
    raw_samples = samples(args.samples)
    measured_limit = args.actual_duration
    measured = [value for value in raw_samples if value.get("elapsedSeconds", 0) <= measured_limit + 1]

    reactive_profile = profile["id"] in ("reactive", "callback-webflux")
    resource_name = "reactive-test-app" if reactive_profile else "test-app"
    jvm_name = "reactiveJvm" if reactive_profile else "jvm"
    cpu_quota = manifest["measurement"]["cpuQuota"]

    cpus = [value for sample in measured
            if (value := cpu_percent((sample.get("containers", {}).get(resource_name) or {}).get("CPUPerc"), cpu_quota)) is not None]
    heaps = [(sample.get("elapsedSeconds", 0), sample[jvm_name]["heapUsedBytes"])
             for sample in measured if sample.get(jvm_name) and sample[jvm_name].get("heapUsedBytes") is not None]
    heap_values = [point[1] for point in heaps]
    middle = [point[1] for point in heaps if measured_limit * 0.4 <= point[0] <= measured_limit * 0.6]
    final = [point[1] for point in heaps if point[0] >= measured_limit * 0.8]
    late = [point for point in heaps if point[0] >= measured_limit * 0.5]
    plateau_delta = (statistics.fmean(final) - statistics.fmean(middle)) if middle and final else None
    late_slope = slope_per_minute(late)

    first_jvm = next((value[jvm_name] for value in measured if value.get(jvm_name)), None)
    last_jvm = next((value[jvm_name] for value in reversed(measured) if value.get(jvm_name)), None)
    gc_count = None if not first_jvm or not last_jvm else max(
        0, last_jvm["gcCollectionCount"] - first_jvm["gcCollectionCount"])
    gc_millis = None if not first_jvm or not last_jvm else max(
        0, last_jvm["gcCollectionTimeMilliseconds"] - first_jvm["gcCollectionTimeMilliseconds"])
    gc_detail = gc_metrics(measured, "reactivePrometheusJvmGc" if reactive_profile else "prometheusJvmGc")
    if gc_detail.get("measured"):
        gc_millis = gc_detail["pauseTotalMilliseconds"]

    configured_duration = profile["durationSeconds"] if args.mode == "full" else 10
    full_duration = args.mode == "full" and args.actual_duration >= configured_duration - 5
    scheduled = metric(summary, "scheduled_operations", "count")
    completed = metric(summary, "completed_operations", "count")
    successful = metric(summary, "successful_operations", "count")
    dropped = metric(summary, "dropped_iterations", "count")
    k6_vus_maximum = metric(summary, "vus_max", "max", None)
    business_errors = metric(summary, "business_errors_attributable_to_observability", "count")
    callback_failures = metric(summary, "callback_failures_attributable_to_observability", "count")
    expected = profile.get("arrivalRatePerSecond", 0) * configured_duration if args.mode == "full" else 0
    if args.mode == "smoke":
        # Smoke proves mechanics only. Approved full-duration/load requirements stay in the
        # manifest and are intentionally not reinterpreted as smoke acceptance thresholds.
        required_operations = 1
    elif expected:
        required_operations = math.ceil(expected * 0.99)
    elif profile["id"] == "journey-query":
        required_operations = manifest["loadCompleteness"]["minimumJourneySuccessfulQueries"]
    elif profile["id"] in ("reactive",):
        required_operations = manifest["loadCompleteness"]["minimumReactiveCompletedOrCancelledStreams"]
    elif profile["id"] in ("callback-webflux",):
        required_operations = manifest["loadCompleteness"]["minimumCallbackSuccesses"]
    else:
        required_operations = 1
    minimum_successful = min(required_operations, expected) if expected else required_operations
    if expected and expected >= manifest["loadCompleteness"]["minimumHighThroughputSuccessfulOperations"]:
        minimum_successful = max(minimum_successful, manifest["loadCompleteness"]["minimumHighThroughputSuccessfulOperations"])
    load_satisfied = (scheduled >= required_operations and successful >= minimum_successful
                      and dropped <= max(0, expected * 0.01))

    application_queue_events_ok = all(health_after.get(name, 0) <= health_after.get(cap, 0) for name, cap in (
        ("highQueueEvents", "highEventCap"), ("normalQueueEvents", "normalEventCap")))
    application_queue_bytes_ok = all(health_after.get(name, 0) <= health_after.get(cap, 0) for name, cap in (
        ("highQueueBytes", "highByteCap"), ("normalQueueBytes", "normalByteCap")))
    if reactive_profile:
        reactive_queue_events_ok = all(reactive.get(name, 0) <= reactive.get(cap, 0) for name, cap in (
            ("telemetryHighQueueEvents", "telemetryHighEventCap"),
            ("telemetryNormalQueueEvents", "telemetryNormalEventCap")))
        reactive_queue_bytes_ok = all(reactive.get(name, 0) <= reactive.get(cap, 0) for name, cap in (
            ("telemetryHighQueueBytes", "telemetryHighByteCap"),
            ("telemetryNormalQueueBytes", "telemetryNormalByteCap")))
        queue_events_ok = application_queue_events_ok and reactive_queue_events_ok
        queue_bytes_ok = application_queue_bytes_ok and reactive_queue_bytes_ok
    else:
        reactive_queue_events_ok = True
        reactive_queue_bytes_ok = True
        queue_events_ok = application_queue_events_ok
        queue_bytes_ok = application_queue_bytes_ok
    deadlock = any(bool((value.get(jvm_name) or {}).get("deadlockDetected")) for value in measured)
    oom = bool(containers.get("oomKilled")) or bool(diagnostics.get("oomDetected"))
    leak = (plateau_delta is None or late_slope is None
            or plateau_delta > manifest["jvm"]["heapMaximumBytes"] * 0.10
            or late_slope > manifest["jvm"]["heapMaximumBytes"] * 0.005)

    business_p50 = metric(summary, "business_latency", "p(50)", None)
    business_p95 = metric(summary, "business_latency", "p(95)", None)
    business_p99 = metric(summary, "business_latency", "p(99)", None)
    if profile["id"].startswith("callback"):
        business_p50 = metric(summary, "callback_latency", "p(50)", business_p50)
        business_p95 = metric(summary, "callback_latency", "p(95)", business_p95)
        business_p99 = metric(summary, "callback_latency", "p(99)", business_p99)
    if profile["id"] == "journey-query":
        business_p50 = metric(summary, "journey_latency", "p(50)", None)
        business_p95 = metric(summary, "journey_latency", "p(95)", None)
        business_p99 = metric(summary, "journey_latency", "p(99)", None)

    baseline_metrics = baseline.get("metrics", {})
    def relative(current, key):
        old = baseline_metrics.get(key)
        if current is None or old in (None, 0):
            return None
        return (current - old) / old * 100.0

    cpu_mean = statistics.fmean(cpus) if cpus else None
    heap_peak = max(heap_values) if heap_values else None
    heap_final_mean = statistics.fmean(final) if final else None
    p95_degradation = relative(business_p95, "p95Milliseconds")
    p99_degradation = relative(business_p99, "p99Milliseconds")
    cpu_increase = relative(cpu_mean, "normalizedCpuMean")
    heap_increase = relative(heap_peak, "heapPeakBytes")
    baseline_heap_final = baseline_metrics.get("heapFinalMeanBytes")
    heap_plateau_above_baseline_mib = (None if heap_final_mean is None or baseline_heap_final is None
                                       else max(0, heap_final_mean - baseline_heap_final) / MIB)
    p99_delta = None if business_p99 is None or baseline_metrics.get("p99Milliseconds") is None else (
        business_p99 - baseline_metrics["p99Milliseconds"])
    p99_allowed_delta = None if baseline_metrics.get("p99Milliseconds") is None else max(
        baseline_metrics["p99Milliseconds"] * 0.02, 1.0)
    p99_regression_ratio = None if p99_delta is None or p99_allowed_delta in (None, 0) else (
        p99_delta / p99_allowed_delta * 100.0)
    cpu_points = None if cpu_mean is None or baseline_metrics.get("normalizedCpuMean") is None else (
        cpu_mean - baseline_metrics["normalizedCpuMean"])

    # Disabled auto-configuration installs no observation engine, dispatcher, interceptor, or
    # producer hook. Treat zero added local work as a structural result only when the runtime
    # counters prove that boundary; never substitute zero for a missing enabled-SDK measurement.
    disabled_boundary_proven = bool(
        profile["id"] == "disabled"
        and health_before.get("state") == "DISABLED"
        and health_after.get("state") == "DISABLED"
        and int(health_after.get("captureAttempts", -1)) == 0
        and "producer" not in timing
    )
    disabled_added_local_p99 = 0.0 if disabled_boundary_proven else None
    disabled_cpu_points = 0.0 if disabled_boundary_proven and cpu_mean is not None else None

    def delta(after: dict, before: dict, name: str) -> int:
        return int(after.get(name, 0)) - int(before.get(name, 0))

    application_capture_attempts = delta(health_after, health_before, "captureAttempts")
    application_enqueued = delta(health_after, health_before, "enqueued")
    application_drops = delta(health_after, health_before, "totalDrops")
    reactive_capture_attempts = delta(reactive, reactive_before, "telemetryCaptureAttempts")
    reactive_enqueued = delta(reactive, reactive_before, "telemetryEnqueued")
    reactive_drops = delta(reactive, reactive_before, "telemetryDrops")

    callback_initiations = metric(summary, "callback_successes", "count")
    intentional_callback_failures = metric(summary, "intentional_callback_processing_failures", "count")
    callbacks_received = delta(callbacks, callbacks_before, "callbacksReceived")
    callbacks_processed = delta(callbacks, callbacks_before, "callbacksProcessed")
    callback_processing_failures = delta(callbacks, callbacks_before, "callbackProcessingFailures")
    callback_responses_sent = delta(callbacks, callbacks_before, "callbackResponsesSent")
    callback_write_failures = delta(callbacks, callbacks_before, "callbackResponseWriteFailures")
    callback_responses_200 = delta(callbacks, callbacks_before, "callbackResponses200")
    callback_responses_202 = delta(callbacks, callbacks_before, "callbackResponses202")
    callback_responses_4xx = delta(callbacks, callbacks_before, "callbackResponses4xx")
    callback_responses_5xx = delta(callbacks, callbacks_before, "callbackResponses5xx")
    reactive_subscriptions = delta(reactive, reactive_before, "subscriptions")
    reactive_completed = delta(reactive, reactive_before, "completed")
    reactive_cancelled = delta(reactive, reactive_before, "cancelled")
    reactive_errors = delta(reactive, reactive_before, "errors")
    reactive_context_conflicts = delta(reactive, reactive_before, "contextConflicts")
    reactive_double_subscriptions = delta(reactive, reactive_before, "doubleSubscriptions")
    reactive_double_terminals = delta(reactive, reactive_before, "doubleTerminalEvents")
    reactive_elements = delta(reactive, reactive_before, "elementsEmitted")
    reactive_terminal_total = reactive_completed + reactive_cancelled + reactive_errors
    reactive_initial_active = int(reactive_before.get("active", 0))
    reactive_active = int(reactive.get("active", 0))
    reactive_maximum_active = int(reactive.get("maximumActive", 0))
    reactive_deferred_active = int(reactive.get("deferredActive", 0))
    reactive_maximum_deferred_active = int(reactive.get("maximumDeferredActive", 0))
    reactive_deferred_capacity = int(reactive.get("deferredCapacity", 0))
    callback_lifecycle_complete = callbacks_processed + callback_processing_failures >= callbacks_received
    callback_delivery_complete = callbacks_received >= callback_initiations
    callback_processing_failures_expected = callback_processing_failures == intentional_callback_failures
    status_changed = metric(summary, "status_changed", "count")
    exception_changed = metric(summary, "exception_changed", "count")
    body_changed = metric(summary, "body_changed", "count")
    reactive_incomplete = metric(summary, "reactive_incomplete_responses", "count")
    journey_query_mix = {
        "applicationId": metric(summary, "journey_queries{queryType:applicationId}", "count"),
        "loanId": metric(summary, "journey_queries{queryType:loanId}", "count"),
        "correlationId": metric(summary, "journey_queries{queryType:correlationId}", "count"),
        "partnerReferenceId": metric(summary, "journey_queries{queryType:partnerReferenceId}", "count"),
        "callbackReferenceId": metric(summary, "journey_queries{queryType:callbackReferenceId}", "count"),
        "unifiedJourney": metric(summary, "journey_queries{queryType:journey}", "count"),
        "detail": metric(summary, "journey_queries{queryType:detail}", "count"),
    }
    journey_age_mix = {
        "last24Hours": metric(summary, "journey_queries{ageBucket:recent}", "count"),
        "days1To8": metric(summary, "journey_queries{ageBucket:middle}", "count"),
        "days8To16": metric(summary, "journey_queries{ageBucket:old}", "count"),
    }
    allocation = jfr_allocation_metrics(
        args.jfr_file, args.started_at, args.ended_at, successful, args.actual_duration)

    metrics = {
        "scheduledOperations": scheduled,
        "completedOperations": completed,
        "successfulOperations": successful,
        "droppedIterations": dropped,
        "k6VusMaximum": k6_vus_maximum,
        "requiredOperations": required_operations,
        "loadSatisfied": load_satisfied,
        "businessErrorsAttributableToObservability": business_errors,
        "callbackFailuresAttributableToObservability": callback_failures,
        "callbackInitiations": callback_initiations,
        "callbacksReceived": callbacks_received,
        "callbacksProcessed": callbacks_processed,
        "callbackProcessingFailures": callback_processing_failures,
        "intentionalCallbackProcessingFailures": intentional_callback_failures,
        "callbackResponsesSent": callback_responses_sent,
        "callbackResponseWriteFailures": callback_write_failures,
        "callbackResponseStatuses": {"200": callback_responses_200, "202": callback_responses_202,
                                     "4xx": callback_responses_4xx, "5xx": callback_responses_5xx},
        "callbackDeliveryComplete": callback_delivery_complete,
        "callbackLifecycleComplete": callback_lifecycle_complete,
        "callbackProcessingFailuresExpected": callback_processing_failures_expected,
        "p50Milliseconds": business_p50,
        "p95Milliseconds": business_p95,
        "p99Milliseconds": business_p99,
        "p95DegradationPercent": p95_degradation,
        "p99DegradationPercent": p99_degradation,
        "p99DeltaMilliseconds": p99_delta,
        "p99AllowedDeltaMilliseconds": p99_allowed_delta,
        "p99RegressionLimitRatioPercent": p99_regression_ratio,
        "normalizedCpuMean": cpu_mean,
        "normalizedCpuMedian": statistics.median(cpus) if cpus else None,
        "normalizedCpuP95": percentile(cpus, 0.95),
        "normalizedCpuMaximum": max(cpus) if cpus else None,
        "cpuIncreasePercent": cpu_increase,
        "cpuIncreasePercentagePoints": cpu_points,
        "heapMeanBytes": statistics.fmean(heap_values) if heap_values else None,
        "heapMedianBytes": statistics.median(heap_values) if heap_values else None,
        "heapP95Bytes": percentile(heap_values, 0.95),
        "heapPeakBytes": heap_peak,
        "heapIncreasePercent": heap_increase,
        "heapFinalPlateauDeltaBytes": plateau_delta,
        "heapLateSlopeBytesPerMinute": late_slope,
        "heapFinalMeanBytes": heap_final_mean,
        "heapPlateauAboveConfiguredCapsMiB": heap_plateau_above_baseline_mib,
        "gcCollectionCount": gc_count,
        "gcPauseTotalMilliseconds": gc_millis,
        "gcPauseP95Milliseconds": gc_detail.get("pauseP95Milliseconds"),
        "gcPauseMaximumUpperBoundMilliseconds": gc_detail.get("pauseMaximumUpperBoundMilliseconds"),
        "fullGcCount": gc_detail.get("fullGcCount"),
        "producerMeanMicroseconds": (timing.get("producer") or {}).get("meanMicroseconds", 0 if profile["sdkState"] == "disabled" or args.baseline else None),
        "producerP99Microseconds": (timing.get("producer") or {}).get("p99Microseconds", 0 if args.baseline else None),
        "addedLocalP99Microseconds": disabled_added_local_p99,
        "allocationsAfterWarmPath": 0 if disabled_boundary_proven else None,
        "allocationMeasured": allocation.get("measured", False),
        "allocationSampleCount": allocation.get("sampleCount"),
        "totalAllocatedBytesEstimate": allocation.get("totalAllocatedBytesEstimate"),
        "allocatedBytesPerSuccessfulOperation": allocation.get("allocatedBytesPerSuccessfulOperation"),
        "allocationRateBytesPerSecond": allocation.get("allocationRateBytesPerSecond"),
        "allocationMeasurementMethod": allocation.get("method"),
        "allocationMeasurementReason": allocation.get("reason"),
        "disabledBoundaryProven": disabled_boundary_proven,
        "offerP99Microseconds": (timing.get("queue-offer") or {}).get("p99Microseconds"),
        "localCaptureP99Microseconds": (timing.get("callback-capture") or {}).get("p99Microseconds"),
        "queueEventsWithinCaps": queue_events_ok,
        "queueBytesWithinCaps": queue_bytes_ok,
        "applicationQueueEventsWithinCaps": application_queue_events_ok,
        "applicationQueueBytesWithinCaps": application_queue_bytes_ok,
        "reactiveQueueEventsWithinCaps": reactive_queue_events_ok,
        "reactiveQueueBytesWithinCaps": reactive_queue_bytes_ok,
        "applicationCaptureAttemptsDelta": application_capture_attempts,
        "applicationEnqueuedDelta": application_enqueued,
        "applicationTelemetryDrops": application_drops,
        "reactiveCaptureAttemptsDelta": reactive_capture_attempts,
        "reactiveEnqueuedDelta": reactive_enqueued,
        "reactiveTelemetryDrops": reactive_drops,
        "telemetryDrops": application_drops + (reactive_drops if reactive_profile else 0),
        "captureAttemptsDelta": application_capture_attempts + (reactive_capture_attempts if reactive_profile else 0),
        "enqueuedDelta": application_enqueued + (reactive_enqueued if reactive_profile else 0),
        "binaryOmissionRecordCount": records.get("binaryOmissionRecordCount", 0),
        "binaryOrOversizeOmissionRecordCount": records.get("binaryOrOversizeOmissionRecordCount", 0),
        "maximumSanitizedPayloadBytes": records.get("maximumSanitizedPayloadBytes"),
        "rawPayloadsExposed": records.get("rawPayloadsExposed"),
        "lokiBinaryPayloadMatches": diagnostics.get("lokiBinaryPayloadMatches"),
        "lokiPayloadScanMeasured": diagnostics.get("lokiPayloadScanMeasured"),
        "lokiServiceScanMeasured": diagnostics.get("lokiServiceScanMeasured"),
        "lokiTestApplicationRecords": diagnostics.get("lokiTestApplicationRecords"),
        "lokiReactiveApplicationRecords": diagnostics.get("lokiReactiveApplicationRecords"),
        "deadlockDetected": deadlock,
        "oomDetected": oom,
        "memoryLeakDetected": leak,
        "contextCrossingDetected": reactive_context_conflicts != 0,
        "reactiveSubscriptions": reactive_subscriptions,
        "reactiveCompleted": reactive_completed,
        "reactiveCancelled": reactive_cancelled,
        "reactiveErrors": reactive_errors,
        "reactiveTerminalTotal": reactive_terminal_total,
        "reactiveInitialActive": reactive_initial_active,
        "reactiveActiveAfterCooldown": reactive_active,
        "reactiveMaximumActive": reactive_maximum_active,
        "reactiveDeferredActive": reactive_deferred_active,
        "reactiveMaximumDeferredActive": reactive_maximum_deferred_active,
        "reactiveDeferredCapacity": reactive_deferred_capacity,
        "reactiveDoubleSubscriptions": reactive_double_subscriptions,
        "reactiveDoubleTerminalEvents": reactive_double_terminals,
        "reactiveElementsEmitted": reactive_elements,
        "reactiveIncompleteResponses": reactive_incomplete,
        "statusChanged": status_changed != 0,
        "exceptionChanged": exception_changed != 0,
        "bodyChanged": body_changed != 0,
        "payloadStatuses": records.get("payloadStatuses", {}),
        "scenarioCounts": {
            "mixedPdfDocuments": metric(summary, "mixed_pdf_documents", "count"),
            "mixedImages": metric(summary, "mixed_images", "count"),
            "mixedUnknownBinary": metric(summary, "mixed_unknown_binary", "count"),
            "mixedMalformed": metric(summary, "mixed_malformed", "count"),
            "asyncDuplicateCallbacks": metric(summary, "async_duplicate_callbacks", "count"),
            "asyncCallbackRetries": metric(summary, "async_callback_retries", "count"),
            "asyncMultipleCallbacks": metric(summary, "async_multiple_callbacks", "count"),
            "asyncCallbackPdf": metric(summary, "async_callback_pdf", "count"),
            "asyncCallbackImage": metric(summary, "async_callback_image", "count"),
            "asyncCallbackProcessingFailure": metric(summary, "async_callback_processing_failure", "count"),
        },
        "mixedDistribution": {
            "syncMetadataSuccess": metric(summary, "mixed_sync_success", "count"),
            "asyncJourneyMetadataSuccess": metric(summary, "mixed_async_journey", "count"),
            "partner4xx": metric(summary, "mixed_partner_4xx", "count"),
            "partner5xx": metric(summary, "mixed_partner_5xx", "count"),
            "timeout": metric(summary, "mixed_timeout", "count"),
            "networkFailure": metric(summary, "mixed_network_failure", "count"),
            "fullSanitizedLargeJson": metric(summary, "mixed_large_json", "count"),
            "binaryDocument": metric(summary, "mixed_binary_document", "count"),
        },
        "callbackCompletionMix": {
            "inline": metric(summary, "callback_inline", "count"),
            "shortDeferred": metric(summary, "callback_short_deferred", "count"),
            "longDeferred": metric(summary, "callback_long_deferred", "count"),
        },
        "journeyLimitViolations": metric(summary, "journey_limit_violations", "count"),
        "journeyTenantViolations": metric(summary, "journey_tenant_violations", "count"),
        "journeyEmptyResults": metric(summary, "journey_empty_results", "count"),
        "journeyResponseBytesMaximum": metric(summary, "journey_response_bytes", "max", None),
        "journeyQueryMix": journey_query_mix,
        "journeyAgeMix": journey_age_mix,
        "journeyCollisionQueries": metric(summary, "journey_queries{collision:true}", "count"),
    }

    checks: list[dict] = []
    failures: list[str] = []
    for threshold_id, actual, operator, expected_value, measured_value in (
        ("full-duration", full_duration, "==", args.mode == "full", True),
        ("full-load", load_satisfied, "==", True, True),
        ("business-errors", business_errors, "==", 0, True),
        ("callback-failures", callback_failures, "==", 0, True),
        ("deadlock", deadlock, "==", False, True),
        ("oom", oom, "==", False, True),
        ("container-restarts", containers.get("totalRestarts"), "==", 0, "totalRestarts" in containers),
        ("container-health", containers.get("unhealthyDetected"), "==", False, "unhealthyDetected" in containers),
        ("sensitive-data-leak", diagnostics.get("sensitiveDataLeakDetected"), "==", False,
         "sensitiveDataLeakDetected" in diagnostics),
        ("raw-payload-exposure", records.get("rawPayloadsExposed"), "==", False,
         "rawPayloadsExposed" in records),
        ("sanitized-payload-bound", records.get("maximumSanitizedPayloadBytes"), "<=", 32 * 1024,
         "maximumSanitizedPayloadBytes" in records),
        ("loki-binary-payload", diagnostics.get("lokiBinaryPayloadMatches"), "==", 0,
         diagnostics.get("lokiPayloadScanMeasured") is True),
        ("bounded-queue-events", queue_events_ok, "==", True, True),
        ("bounded-queue-bytes", queue_bytes_ok, "==", True, True),
        ("heap-plateau", plateau_delta, "<=", manifest["jvm"]["heapMaximumBytes"] * 0.10, plateau_delta is not None),
        ("heap-slope", late_slope, "<=", manifest["jvm"]["heapMaximumBytes"] * 0.005, late_slope is not None),
        ("gc-total-pause", gc_millis, "<=", configured_duration * 1000 * 0.02, gc_millis is not None),
        ("gc-pause-p95", metrics["gcPauseP95Milliseconds"], "<=", 250, gc_detail.get("measured", False)),
        ("gc-pause-maximum", metrics["gcPauseMaximumUpperBoundMilliseconds"], "<=", 2000,
         gc_detail.get("measured", False) and metrics["gcPauseMaximumUpperBoundMilliseconds"] is not None),
        ("full-gc-count", metrics["fullGcCount"], "<=", 2, gc_detail.get("measured", False)),
    ):
        reason = threshold(checks, threshold_id, actual, operator, expected_value, measured_value)
        if reason:
            failures.append(reason)

    baseline_valid = None
    baseline_age_seconds = None
    if not args.baseline and profile["id"] not in ("disabled", "journey-query"):
        try:
            baseline_ended = datetime.fromisoformat(baseline["endedAt"].replace("Z", "+00:00"))
            comparison_started = datetime.fromisoformat(args.started_at.replace("Z", "+00:00"))
            baseline_age_seconds = (comparison_started - baseline_ended).total_seconds()
        except (KeyError, TypeError, ValueError):
            baseline_age_seconds = None
        baseline_valid = bool(
            baseline.get("passed")
            and baseline.get("gitCommit") == args.git_commit
            and baseline.get("baselineWorkloadHash") == args.workload_hash
            and baseline.get("environmentFingerprint") == environment_fingerprint
            and baseline_age_seconds is not None
            and 0 <= baseline_age_seconds <= manifest["comparison"]["reuseMaximumAgeSeconds"])
        reason = threshold(checks, "matched-baseline-valid", baseline_valid, "==", True, True)
        if reason:
            failures.append("MATCHED_BASELINE_INVALID")

    drop_exact = metrics["captureAttemptsDelta"] == metrics["enqueuedDelta"] + metrics["telemetryDrops"]
    profile_checks = {
        "disabled": [("added-local-p99", metrics["addedLocalP99Microseconds"], "<=", 25),
                     ("cpu-increase", disabled_cpu_points, "<=", 1)],
        "metadata": [("producer-p99", metrics["producerP99Microseconds"], "<=", 150),
                     ("producer-mean", metrics["producerMeanMicroseconds"], "<=", 50),
                     ("request-p99-regression-worst-guard", p99_regression_ratio, "<=", 125)],
        "full-sanitized": [
            ("producer-p99", None if metrics["producerP99Microseconds"] is None else metrics["producerP99Microseconds"] / 1000, "<=", 2),
            ("producer-mean", metrics["producerMeanMicroseconds"], "<=", 500),
            ("cpu-increase-worst-guard-points", cpu_points, "<=", 6.25)],
        "saturation": [("offer-p99", metrics["offerP99Microseconds"], "<=", 100),
                       ("business-errors", business_errors, "==", 0),
                       ("callback-failures", callback_failures, "==", 0),
                       ("queue-events", queue_events_ok, "==", True),
                       ("queue-bytes", queue_bytes_ok, "==", True),
                       ("heap-cap", heap_plateau_above_baseline_mib, "<=", 32),
                       ("telemetry-dropped", metrics["telemetryDrops"] > 0, "==", True)],
        "mixed-soak": [("memory-leak", leak, "==", False),
                       ("deadlock", deadlock, "==", False),
                       ("context-crossing", metrics["contextCrossingDetected"], "==", False),
                       ("drop-accounting", drop_exact, "==", True)],
        "reactive": [("data-buffer-leaks", diagnostics.get("dataBufferLeakWarnings"), "==", 0),
                     ("double-subscriptions", reactive_double_subscriptions, "==", 0),
                     ("double-terminal-events", reactive_double_terminals, "==", 0),
                     ("demand", reactive_incomplete != 0, "==", False),
                     ("accumulation", leak or reactive_active != 0, "==", False)],
        "callback-mvc": [("local-capture-p99", metrics["localCaptureP99Microseconds"], "<=", 250),
                         ("status-unchanged", metrics["statusChanged"], "==", False),
                         ("exception-unchanged", metrics["exceptionChanged"], "==", False),
                         ("body-unchanged", metrics["bodyChanged"], "==", False),
                         ("queue-bounded", queue_events_ok and queue_bytes_ok, "==", True),
                         ("memory-bounded", not leak, "==", True)],
        "callback-webflux": [("data-buffer-leaks", diagnostics.get("dataBufferLeakWarnings"), "==", 0),
                             ("demand", reactive_incomplete != 0, "==", False),
                             ("double-records", reactive_double_terminals, "==", 0),
                             ("context-crossing", metrics["contextCrossingDetected"], "==", False),
                             ("candidate-memory", not leak and queue_events_ok and queue_bytes_ok, "==", True)],
        "journey-query": [
            ("query-p95", None if business_p95 is None else business_p95 / 1000, "<=", 5),
            ("query-max", None if metric(summary, "journey_latency", "max", None) is None else metric(summary, "journey_latency", "max") / 1000, "<=", 10),
            ("time-limit", None if metric(summary, "journey_latency", "max", None) is None else metric(summary, "journey_latency", "max") <= 10000, "==", True),
            ("key-limit", metrics["journeyLimitViolations"] == 0, "==", True),
            ("round-limit", metrics["journeyLimitViolations"] == 0, "==", True),
            ("record-limit", metrics["journeyLimitViolations"] == 0, "==", True),
            ("response-size", metrics["journeyResponseBytesMaximum"], "<=", 2097152),
            ("tenant-isolation", metrics["journeyTenantViolations"], "==", 0)],
    }
    for threshold_id, actual, operator, expected_value in ([] if args.baseline else profile_checks.get(profile["id"], [])):
        reason = threshold(checks, threshold_id, actual, operator, expected_value, actual is not None)
        if reason:
            failures.append(reason)

    if not args.baseline and profile["id"] == "disabled":
        checks.append({
            "id": "allocations",
            "operator": "==",
            "expected": 0,
            "actual": 0 if disabled_boundary_proven else None,
            "measured": False,
            "applicable": False,
            "status": "STRUCTURALLY_ABSENT_DISABLED_OBSERVATION_BOUNDARY"
                      if disabled_boundary_proven else "DISABLED_BOUNDARY_NOT_PROVEN",
            "passed": disabled_boundary_proven,
        })

    if not args.baseline and profile["id"] not in ("disabled", "journey-query"):
        for threshold_id, actual, limit in (
            ("p95-degradation-worst-guard", p95_degradation,
             manifest["comparison"]["medianLimitsPercent"]["p95Degradation"] * manifest["comparison"]["worstRunMultiplier"]),
            ("p99-degradation-worst-guard", p99_degradation,
             manifest["comparison"]["medianLimitsPercent"]["p99Degradation"] * manifest["comparison"]["worstRunMultiplier"]),
            ("cpu-increase-worst-guard", cpu_increase,
             manifest["comparison"]["medianLimitsPercent"]["cpuIncrease"] * manifest["comparison"]["worstRunMultiplier"]),
            ("heap-increase-worst-guard", heap_increase,
             manifest["comparison"]["medianLimitsPercent"]["peakHeapIncrease"] * manifest["comparison"]["worstRunMultiplier"]),
        ):
            reason = threshold(checks, threshold_id, actual, "<=", limit, actual is not None)
            if reason:
                failures.append(reason)

    platform_delivery_expected = not args.baseline and profile["id"] not in ("disabled", "journey-query")
    platform_record_count = (metrics["lokiReactiveApplicationRecords"]
                             if profile["id"] == "callback-webflux"
                             else metrics["lokiTestApplicationRecords"])
    platform_delivery_passed = (not platform_delivery_expected or
                                (metrics["lokiServiceScanMeasured"] is True
                                 and platform_record_count is not None
                                 and platform_record_count > 0))
    if platform_delivery_expected:
        checks.append({"id": "application-telemetry-reached-loki", "operator": ">", "expected": 0,
                       "actual": platform_record_count,
                       "measured": metrics["lokiServiceScanMeasured"] is True,
                       "passed": platform_delivery_passed})
        if not platform_delivery_passed:
            failures.append("APPLICATION_TELEMETRY_NOT_VISIBLE_IN_LOKI")

    callback_profiles = {"saturation", "mixed-soak", "callback-mvc"}
    if profile["id"] in callback_profiles:
        expected_callbacks = {
            "saturation": math.floor(configured_duration * 2000 / 167),
            "mixed-soak": math.floor(configured_duration * 1000 * 0.15),
            "callback-mvc": math.floor(configured_duration * 500),
        }[profile["id"]]
        required_callbacks = (1 if args.mode == "smoke" else
                              max(manifest["loadCompleteness"]["minimumCallbackSuccesses"],
                                  math.floor(expected_callbacks * 0.99)))
        for check_id, actual, operator, expected_value in (
            ("callback-initiations-complete", callback_initiations, ">=", required_callbacks),
            ("callbacks-received", callbacks_received, ">=", required_callbacks),
            ("callback-delivery-complete", callback_delivery_complete, "==", True),
            ("callback-lifecycle-complete", callback_lifecycle_complete, "==", True),
            ("callback-response-complete", callback_responses_sent >= callbacks_received, "==", True),
            ("callback-processing-failures-expected", callback_processing_failures_expected, "==", True),
            ("callback-response-write-failures", callback_write_failures, "==", 0),
        ):
            if operator == ">=":
                passed_check = actual is not None and actual >= expected_value
                checks.append({"id": check_id, "operator": operator, "expected": expected_value,
                               "actual": actual, "measured": actual is not None, "passed": passed_check})
                if not passed_check:
                    failures.append("REQUIRED_CALLBACK_COMPLETION_NOT_SATISFIED:" + check_id)
            else:
                reason = threshold(checks, check_id, actual, operator, expected_value, actual is not None)
                if reason:
                    failures.append(reason)

    if profile["id"] in ("reactive", "callback-webflux"):
        required_reactive = (1 if args.mode == "smoke" else
                             manifest["loadCompleteness"]["minimumReactiveCompletedOrCancelledStreams"]
                             if profile["id"] == "reactive" else
                             manifest["loadCompleteness"]["minimumCallbackSuccesses"])
        configured_concurrency_reached = (
            reactive_maximum_active >= profile["concurrency"] if profile["id"] == "reactive"
            else k6_vus_maximum is not None and k6_vus_maximum >= profile["concurrency"])
        reactive_checks = (
            ("reactive-required-terminal-count", reactive_terminal_total >= required_reactive),
            ("reactive-terminal-accounting",
             reactive_terminal_total == reactive_subscriptions + reactive_initial_active - reactive_active),
            ("reactive-no-errors", reactive_errors == 0),
            ("reactive-no-double-subscriptions", reactive_double_subscriptions == 0),
            ("reactive-no-double-terminal-events", reactive_double_terminals == 0),
            ("reactive-no-active-after-cooldown", reactive_active == 0),
            ("reactive-no-deferred-active-after-cooldown", reactive_deferred_active == 0),
            ("reactive-deferred-work-bounded",
             reactive_deferred_capacity > 0
             and reactive_maximum_deferred_active <= reactive_deferred_capacity),
            # Streams have a 20-second lifetime, so server-active concurrency is the governing
            # reactive metric. Callback completion is intentionally 25-75ms/500ms/2s; its
            # existing 500-call concurrency criterion is therefore proven by K6 VUs.
            ("reactive-full-concurrency", args.mode != "full" or configured_concurrency_reached),
            ("reactive-complete-demand", reactive_incomplete == 0),
        )
        for check_id, actual in reactive_checks:
            reason = threshold(checks, check_id, actual, "==", True, True)
            if reason:
                failures.append(reason)

        target_cancelled = 25 if profile["id"] == "reactive" else 20
        actual_cancelled = None if reactive_terminal_total == 0 else (
            reactive_cancelled * 100.0 / reactive_terminal_total)
        cancelled_mix_valid = actual_cancelled is not None and abs(actual_cancelled - target_cancelled) <= 1.0
        checks.append({"id": "reactive-cancellation-mix", "operator": "within",
                       "expected": target_cancelled, "tolerance": 1.0,
                       "actual": actual_cancelled, "measured": actual_cancelled is not None,
                       "passed": cancelled_mix_valid})
        if not cancelled_mix_valid:
            failures.append("REACTIVE_CANCELLATION_DISTRIBUTION_INVALID")

    if not args.baseline and profile["id"] == "mixed-soak":
        distribution = metrics["mixedDistribution"]
        total = sum(distribution.values())
        targets = {"syncMetadataSuccess": 65, "asyncJourneyMetadataSuccess": 15,
                   "partner4xx": 4, "partner5xx": 3, "timeout": 2, "networkFailure": 1,
                   "fullSanitizedLargeJson": 5, "binaryDocument": 5}
        for name, target in targets.items():
            actual = None if total == 0 else distribution[name] * 100.0 / total
            tolerance = 1.0 if name in ("syncMetadataSuccess", "asyncJourneyMetadataSuccess",
                                        "fullSanitizedLargeJson", "binaryDocument") else 0.5
            passed_distribution = actual is not None and abs(actual - target) <= tolerance
            checks.append({"id": "mixed-distribution-" + name, "operator": "within",
                           "expected": target, "tolerance": tolerance, "actual": actual,
                           "measured": actual is not None, "passed": passed_distribution})
            if not passed_distribution:
                failures.append("MIXED_DISTRIBUTION_INVALID:" + name)
        if metrics["binaryOmissionRecordCount"] + metrics["binaryOrOversizeOmissionRecordCount"] <= 0:
            failures.append("BINARY_OMISSION_NOT_OBSERVED")
        checks.append({"id": "drop-accounting-exact", "operator": "==", "expected": True,
                       "actual": drop_exact, "measured": True, "passed": drop_exact})
        if not drop_exact:
            failures.append("DROP_ACCOUNTING_NOT_EXACT")

    if profile["id"] in ("callback-mvc", "callback-webflux"):
        callback_mix = metrics["callbackCompletionMix"]
        total = sum(callback_mix.values())
        for name, target in {"inline": 90, "shortDeferred": 8, "longDeferred": 2}.items():
            actual = None if total == 0 else callback_mix[name] * 100.0 / total
            passed_mix = actual is not None and abs(actual - target) <= 0.5
            checks.append({"id": "callback-completion-" + name, "operator": "within",
                           "expected": target, "tolerance": 0.5, "actual": actual,
                           "measured": actual is not None, "passed": passed_mix})
            if not passed_mix:
                failures.append("CALLBACK_COMPLETION_DISTRIBUTION_INVALID:" + name)

    if not args.baseline and profile["id"] == "journey-query":
        for check_id, actual in (("journey-limit-violations", metrics["journeyLimitViolations"]),
                                 ("journey-tenant-violations", metrics["journeyTenantViolations"]),
                                 ("journey-empty-results", metrics["journeyEmptyResults"])):
            reason = threshold(checks, check_id, actual, "==", 0, True)
            if reason:
                failures.append(reason)
        query_total = sum(journey_query_mix.values())
        for name, target in profile["workload"]["queryMixPercent"].items():
            actual = None if query_total == 0 else journey_query_mix[name] * 100.0 / query_total
            passed_mix = actual is not None and abs(actual - target) <= 1.0
            checks.append({"id": "journey-query-mix-" + name, "operator": "within",
                           "expected": target, "tolerance": 1.0, "actual": actual,
                           "measured": actual is not None, "passed": passed_mix})
            if not passed_mix:
                failures.append("JOURNEY_QUERY_DISTRIBUTION_INVALID:" + name)
        age_total = sum(journey_age_mix.values())
        for name, target in profile["workload"]["recordAgeMixPercent"].items():
            actual = None if age_total == 0 else journey_age_mix[name] * 100.0 / age_total
            passed_mix = actual is not None and abs(actual - target) <= 1.0
            checks.append({"id": "journey-age-mix-" + name, "operator": "within",
                           "expected": target, "tolerance": 1.0, "actual": actual,
                           "measured": actual is not None, "passed": passed_mix})
            if not passed_mix:
                failures.append("JOURNEY_AGE_DISTRIBUTION_INVALID:" + name)
        collision_percent = None if query_total == 0 else metrics["journeyCollisionQueries"] * 100.0 / query_total
        collision_valid = collision_percent is not None and abs(
            collision_percent - profile["workload"]["sameIdentifierCollisionPercent"]) <= 1.0
        checks.append({"id": "journey-collision-mix", "operator": "within",
                       "expected": profile["workload"]["sameIdentifierCollisionPercent"],
                       "tolerance": 1.0, "actual": collision_percent,
                       "measured": collision_percent is not None, "passed": collision_valid})
        if not collision_valid:
            failures.append("JOURNEY_COLLISION_DISTRIBUTION_INVALID")

    outage_by_name = {value.get("scenario"): value for value in outage}
    if not args.baseline and profile["id"] == "saturation":
        for name in ("queue-saturation", "alloy-unavailable", "loki-unavailable",
                     "prometheus-unavailable", "grafana-unavailable"):
            value = outage_by_name.get(name)
            passed_outage = bool(value and value.get("unavailableConfirmed") and value.get("restored")
                                 and value.get("captureAttemptsDelta", 0) > 0
                                 and value.get("callbacksReceivedDelta", 0) > 0
                                 and value.get("callbacksProcessedDelta", 0) > 0
                                 and value.get("callbackFailuresDelta", 0) == 0
                                 and value.get("callbackResponsesDelta", 0) >= value.get("callbacksReceivedDelta", 0)
                                 and value.get("reactiveCallbackHttpStatus") == 200)
            if name == "queue-saturation":
                passed_outage = passed_outage and value.get("dropsDelta", 0) > 0
            checks.append({"id": "outage-" + name, "operator": "==", "expected": True,
                           "actual": passed_outage, "measured": value is not None, "passed": passed_outage})
            if not passed_outage:
                failures.append("OUTAGE_EVIDENCE_INVALID:" + name)

    if diagnostics.get("sensitiveDataLeakDetected"):
        failures.append("SENSITIVE_DATA_LEAK_DETECTED")
    if diagnostics.get("dataBufferLeakWarnings", 0) > 0:
        failures.append("DATA_BUFFER_LEAK_WARNING")
    if (metrics["fullGcCount"] or 0) >= 2 and (metrics["heapLateSlopeBytesPerMinute"] or 0) > 0:
        failures.append("REPEATED_FULL_GC_WITH_POSITIVE_HEAP_GROWTH")
    if summary.get("state", {}).get("isStdOutTTY") is None and not summary.get("metrics"):
        failures.append("K6_SUMMARY_MISSING")

    scenario_counts = metrics["scenarioCounts"]
    common_scenario_checks = [
        {"id": "full-duration", "actual": full_duration, "passed": full_duration},
        {"id": "full-load", "actual": load_satisfied, "passed": load_satisfied},
        {"id": "business-continuity", "actual": business_errors, "passed": business_errors == 0},
        {"id": "bounded-memory", "actual": not leak and not oom, "passed": not leak and not oom},
        {"id": "bounded-queues", "actual": queue_events_ok and queue_bytes_ok,
         "passed": queue_events_ok and queue_bytes_ok},
        {"id": "payload-safety", "actual": {
            "rawPayloadsExposed": metrics["rawPayloadsExposed"],
            "lokiBinaryPayloadMatches": metrics["lokiBinaryPayloadMatches"]},
         "passed": metrics["rawPayloadsExposed"] is False and metrics["lokiBinaryPayloadMatches"] == 0},
        {"id": "application-to-platform-delivery", "actual": platform_record_count,
         "passed": platform_delivery_passed},
    ]

    def scenario_specific(scenario: str, execution: str) -> list[dict]:
        if profile["id"] == "saturation":
            value = outage_by_name.get(execution, {})
            return [{"id": "outage-continuity", "actual": value,
                    "passed": bool(value.get("unavailableConfirmed") and value.get("restored")
                                    and value.get("captureAttemptsDelta", 0) > 0
                                    and value.get("callbacksReceivedDelta", 0) > 0
                                    and value.get("callbackFailuresDelta", 0) == 0
                                    and value.get("reactiveCallbackHttpStatus") == 200)}]
        rules = {
            "P01": (profile["sdkState"] == "disabled" and metrics["captureAttemptsDelta"] == 0,
                    {"sdkState": profile["sdkState"], "captureAttemptsDelta": metrics["captureAttemptsDelta"]}),
            "P02": (metrics["captureAttemptsDelta"] > 0, metrics["captureAttemptsDelta"]),
            "P08": (scenario_counts["mixedUnknownBinary"] > 0 and scenario_counts["mixedMalformed"] > 0,
                    {"unknownBinary": scenario_counts["mixedUnknownBinary"], "malformed": scenario_counts["mixedMalformed"]}),
            "P09": (scenario_counts["mixedPdfDocuments"] > 0 and
                    metrics["binaryOmissionRecordCount"] + metrics["binaryOrOversizeOmissionRecordCount"] > 0,
                    scenario_counts["mixedPdfDocuments"]),
            "P10": (scenario_counts["mixedImages"] > 0 and
                    metrics["binaryOmissionRecordCount"] + metrics["binaryOrOversizeOmissionRecordCount"] > 0,
                    scenario_counts["mixedImages"]),
            "P11": (sum(metrics["mixedDistribution"].values()) > 0, metrics["mixedDistribution"]),
            "P12": (reactive_maximum_active >= profile.get("concurrency", 0), reactive_maximum_active),
            "P13": (reactive_errors == 0 and reactive_incomplete == 0 and reactive_context_conflicts == 0
                    and metrics["applicationCaptureAttemptsDelta"] > 0 and platform_delivery_passed,
                    {"errors": reactive_errors, "incomplete": reactive_incomplete,
                     "contextConflicts": reactive_context_conflicts,
                     "webClientCaptureAttempts": metrics["applicationCaptureAttemptsDelta"],
                     "lokiRecords": platform_record_count}),
            "P14": ((callbacks_received >= 1 if profile["id"] == "callback-mvc" else reactive_terminal_total >= 1),
                    {"mvcReceived": callbacks_received, "webfluxTerminal": reactive_terminal_total}),
            "P18": (scenario_counts["asyncCallbackPdf"] > 0 and
                    metrics["binaryOmissionRecordCount"] + metrics["binaryOrOversizeOmissionRecordCount"] > 0,
                    scenario_counts["asyncCallbackPdf"]),
            "P19": (scenario_counts["asyncCallbackImage"] > 0 and
                    metrics["binaryOmissionRecordCount"] + metrics["binaryOrOversizeOmissionRecordCount"] > 0,
                    scenario_counts["asyncCallbackImage"]),
            "P20": (scenario_counts["asyncDuplicateCallbacks"] > 0, scenario_counts["asyncDuplicateCallbacks"]),
            "P21": (scenario_counts["asyncCallbackRetries"] > 0, scenario_counts["asyncCallbackRetries"]),
            "P22": (scenario_counts["asyncMultipleCallbacks"] > 0, scenario_counts["asyncMultipleCallbacks"]),
            "P23": (metrics["mixedDistribution"]["syncMetadataSuccess"] > 0 and
                    metrics["mixedDistribution"]["asyncJourneyMetadataSuccess"] > 0 and callbacks_received > 0,
                    {"sync": metrics["mixedDistribution"]["syncMetadataSuccess"],
                     "async": metrics["mixedDistribution"]["asyncJourneyMetadataSuccess"],
                     "callbacks": callbacks_received}),
            "P24": ((scenario_counts["asyncCallbackProcessingFailure"] > 0
                     if profile["id"] == "mixed-soak" else intentional_callback_failures > 0)
                    and callback_processing_failures_expected,
                    {"scheduledIntentional": intentional_callback_failures,
                     "observedProcessingFailures": callback_processing_failures}),
        }
        passed_value, actual_value = rules.get(scenario, (True, "PROFILE_THRESHOLD_EVIDENCE"))
        return [{"id": "scenario-coverage", "actual": actual_value, "passed": passed_value}]

    scenario_assertions = []
    for execution in ([] if args.baseline else profile["executions"]):
        for scenario in execution["mappedScenarios"]:
            assertions = common_scenario_checks + scenario_specific(scenario, execution["id"])
            scenario_assertions.append({
                "scenarioId": scenario,
                "parentExecution": execution["id"],
                "assertions": assertions,
                "evidence": ["k6-summary.json", "metrics.json", "thresholds.json", "jvm.json",
                             "containers.json", "callbacks.json", "reactive.json", "diagnostics.json"],
                "passed": all(value["passed"] for value in assertions),
            })

    for assertion in scenario_assertions:
        if not assertion["passed"]:
            failures.append("MANDATORY_SCENARIO_ASSERTION_FAILED:" + assertion["scenarioId"])

    passed = args.mode == "full" and not failures
    result = {
        "runId": args.run_id,
        "profileId": profile["id"],
        "executionId": args.execution_id,
        "repetition": args.repetition,
        "mode": args.mode,
        "springProfile": "local",
        "gitCommit": args.git_commit,
        "startedAt": args.started_at,
        "endedAt": args.ended_at,
        "configuredDurationSeconds": configured_duration,
        "actualDurationSeconds": args.actual_duration,
        "actualMeasuredDurationSeconds": args.actual_duration,
        "warmupSeconds": profile["warmupSeconds"] if args.mode == "full" else 3,
        "cooldownSeconds": profile["cooldownSeconds"] if args.mode == "full" else 3,
        "fullDurationSatisfied": full_duration,
        "loadConfiguration": {key: profile[key] for key in ("arrivalRatePerSecond", "concurrency", "virtualUsers") if key in profile},
        "scheduledOperations": scheduled,
        "completedOperations": completed,
        "successfulOperations": successful,
        "droppedIterations": dropped,
        "baselineRunId": baseline.get("evidenceId"),
        "baselineProfileId": profile.get("baselineProfileId"),
        "baselineWorkloadHash": args.workload_hash,
        "baselineAgeSeconds": baseline_age_seconds,
        "baselineValid": baseline_valid,
        "environmentFingerprint": environment_fingerprint,
        "evidenceId": f"{args.run_id}:{profile['id']}:r{args.repetition}:{'baseline' if args.baseline else 'enabled'}",
        "isMatchedBaseline": args.baseline,
        "metrics": metrics,
        "thresholds": checks,
        "scenarioAssertions": scenario_assertions,
        "allocationMeasured": allocation.get("measured", False),
        "allocationMeasurementStatus": ("INFORMATIONAL_MEASURED" if allocation.get("measured")
                                        else "NOT_MEASURED:" + allocation.get("reason", "UNKNOWN")),
        "passed": passed,
        "failureReasons": sorted(set(failures)),
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output.parent / "metrics.json").write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output.parent / "thresholds.json").write_text(json.dumps(checks, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output.parent / "jvm.json").write_text(json.dumps({key: value for key, value in metrics.items() if "heap" in key.lower() or "gc" in key.lower() or key in ("deadlockDetected", "oomDetected")}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output.parent / "summary.json").write_text(json.dumps({
        "runId": args.run_id, "profileId": profile["id"], "repetition": args.repetition,
        "mode": args.mode, "springProfile": "local", "passed": passed,
        "failureReasons": sorted(set(failures))}, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
