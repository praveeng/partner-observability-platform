#!/usr/bin/env python3
"""Aggregate three repetitions per profile and then the complete nine-profile B003 run."""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import statistics


def load(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def cv(values: list[float]) -> float | None:
    if len(values) < 2 or statistics.fmean(values) == 0:
        return 0.0 if values else None
    return statistics.pstdev(values) / statistics.fmean(values) * 100.0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--run-root", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--git-commit", required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--started-at", required=True)
    parser.add_argument("--ended-at", required=True)
    args = parser.parse_args()
    manifest = load(pathlib.Path(args.manifest))
    root = pathlib.Path(args.run_root)
    profile_results = []
    failures = []
    thresholds_failed = []
    seed_path = root / "journey-seed" / "result.json"
    seed_result = load(seed_path) if seed_path.exists() else {"passed": False}
    if not seed_result.get("passed"):
        failures.append("JOURNEY_SEED_NOT_COMPLETELY_RETAINED")

    for profile in manifest["profiles"]:
        repetition_paths = sorted((root / profile["id"]).glob("repetition-*/result.json"))
        repetitions = [load(path) for path in repetition_paths]
        profile_failures = []
        evidence_completeness = []
        for path in repetition_paths:
            repetition_root = path.parent
            missing = [name for name in manifest["evidence"]["requiredProfileFiles"]
                       if not (repetition_root / name).is_file()]
            evidence_completeness.append({
                "repetition": load(path).get("repetition"),
                "missing": missing,
                "passed": not missing,
            })
            if missing:
                profile_failures.append("MANDATORY_EVIDENCE_MISSING:" + ",".join(missing))
        if len(repetitions) != manifest["repetitions"]:
            profile_failures.append("REPETITION_COUNT_MISMATCH")
        hard_pass = len(repetitions) == manifest["repetitions"] and all(value["passed"] for value in repetitions)
        if profile["id"] not in ("disabled", "journey-query"):
            stability_path = root / "baselines" / profile["id"] / "baseline-stability.json"
            stability = load(stability_path) if stability_path.exists() else {"passed": False}
            if not stability.get("passed"):
                profile_failures.append("INCONCLUSIVE_ENVIRONMENT_UNSTABLE")
        quantitative = {}
        for metric, limit_name in (("p95DegradationPercent", "p95Degradation"),
                                   ("p99DegradationPercent", "p99Degradation"),
                                   ("cpuIncreasePercent", "cpuIncrease"),
                                   ("heapIncreasePercent", "peakHeapIncrease")):
            values = [value["metrics"].get(metric) for value in repetitions]
            values = [value for value in values if value is not None]
            if not values or profile["id"] in ("disabled", "journey-query"):
                quantitative[metric] = {"status": "NOT_APPLICABLE" if profile["id"] in ("disabled", "journey-query") else "NOT_MEASURED"}
                if profile["id"] not in ("disabled", "journey-query"):
                    profile_failures.append("MANDATORY_COMPARISON_NOT_MEASURED:" + metric)
                continue
            limit = manifest["comparison"]["medianLimitsPercent"][limit_name]
            median = statistics.median(values)
            worst = max(values)
            passed = median <= limit and worst <= limit * manifest["comparison"]["worstRunMultiplier"]
            quantitative[metric] = {"values": values, "median": median, "worst": worst,
                                    "medianLimit": limit,
                                    "worstLimit": limit * manifest["comparison"]["worstRunMultiplier"],
                                    "passed": passed}
            if not passed:
                profile_failures.append("QUANTITATIVE_THRESHOLD_FAILED:" + metric)

        if profile["id"] == "metadata" and repetitions:
            ratios = [value["metrics"].get("p99RegressionLimitRatioPercent") for value in repetitions]
            ratios = [value for value in ratios if value is not None]
            passed = len(ratios) == 3 and statistics.median(ratios) <= 100 and max(ratios) <= 125
            quantitative["metadataRequestP99Regression"] = {
                "values": ratios, "median": statistics.median(ratios) if ratios else None,
                "worst": max(ratios) if ratios else None, "medianLimit": 100,
                "worstLimit": 125, "passed": passed}
            if not passed:
                profile_failures.append("QUANTITATIVE_THRESHOLD_FAILED:metadataRequestP99Regression")
        if profile["id"] == "full-sanitized" and repetitions:
            values = [value["metrics"].get("cpuIncreasePercentagePoints") for value in repetitions]
            values = [value for value in values if value is not None]
            passed = len(values) == 3 and statistics.median(values) <= 5 and max(values) <= 6.25
            quantitative["cpuIncreasePercentagePoints"] = {
                "values": values, "median": statistics.median(values) if values else None,
                "worst": max(values) if values else None, "medianLimit": 5,
                "worstLimit": 6.25, "passed": passed}
            if not passed:
                profile_failures.append("QUANTITATIVE_THRESHOLD_FAILED:cpuIncreasePercentagePoints")
        if profile["id"] == "mixed-soak" and repetitions:
            values = [value["metrics"].get("p99DegradationPercent") for value in repetitions]
            values = [value for value in values if value is not None]
            passed = len(values) == 3 and statistics.median(values) <= 5 and max(values) <= 6.25
            quantitative["mixedBusinessP99RegressionPercent"] = {
                "values": values, "median": statistics.median(values) if values else None,
                "worst": max(values) if values else None, "medianLimit": 5,
                "worstLimit": 6.25, "passed": passed}
            if not passed:
                profile_failures.append("QUANTITATIVE_THRESHOLD_FAILED:mixedBusinessP99RegressionPercent")
        if profile["id"] == "saturation" and repetitions:
            deltas = []
            for value in repetitions:
                baseline_path = root / "baselines" / profile["id"] / f"repetition-{value['repetition']}" / "result.json"
                baseline = load(baseline_path) if baseline_path.exists() else {}
                current = value["metrics"].get("heapFinalMeanBytes")
                old = baseline.get("metrics", {}).get("heapFinalMeanBytes")
                if current is not None and old is not None:
                    deltas.append(max(0, current - old) / (1024 * 1024))
            passed = len(deltas) == 3 and all(value <= 32 for value in deltas)
            quantitative["heapPlateauAboveConfiguredCapsMiB"] = {
                "values": deltas, "limit": 32, "passed": passed}
            if not passed:
                profile_failures.append("QUANTITATIVE_THRESHOLD_FAILED:heapPlateauAboveConfiguredCapsMiB")

        repetition_threshold_ids = {
            check.get("id") for value in repetitions for check in value.get("thresholds", [])
        }
        aggregate_thresholds = {}
        if profile["id"] == "metadata":
            aggregate_thresholds["request-p99-regression"] = quantitative.get("metadataRequestP99Regression", {})
        elif profile["id"] == "full-sanitized":
            aggregate_thresholds["cpu-increase"] = quantitative.get("cpuIncreasePercentagePoints", {})
        elif profile["id"] == "mixed-soak":
            aggregate_thresholds["business-p99-regression"] = quantitative.get(
                "mixedBusinessP99RegressionPercent", {})
        manifest_threshold_results = []
        for definition in profile["thresholds"]:
            threshold_id = definition["id"]
            if threshold_id in repetition_threshold_ids:
                relevant = [check for value in repetitions for check in value.get("thresholds", [])
                            if check.get("id") == threshold_id]
                evaluated = len(relevant) == len(repetitions) and all(
                    check.get("measured") or check.get("applicable") is False for check in relevant)
                passed_threshold = evaluated and all(check.get("passed") for check in relevant)
                evidence_scope = "repetition"
                evidence_value = relevant
            else:
                aggregate_value = aggregate_thresholds.get(threshold_id, {})
                evaluated = bool(aggregate_value) and aggregate_value.get("status") != "NOT_MEASURED"
                passed_threshold = evaluated and bool(aggregate_value.get("passed"))
                evidence_scope = "three-repetition-aggregate"
                evidence_value = aggregate_value
            manifest_threshold_results.append({
                "id": threshold_id,
                "definition": definition,
                "evaluationScope": evidence_scope,
                "evaluated": evaluated,
                "passed": passed_threshold,
                "evidence": evidence_value,
            })
            if not evaluated:
                profile_failures.append("MANDATORY_THRESHOLD_NOT_EVALUATED:" + threshold_id)
            elif not passed_threshold:
                profile_failures.append("MANDATORY_THRESHOLD_FAILED:" + threshold_id)

        result = {
            "runId": args.run_id,
            "profileId": profile["id"],
            "mode": args.mode,
            "springProfile": "local",
            "configuredDurationSeconds": profile["durationSeconds"],
            "warmupSeconds": profile["warmupSeconds"],
            "cooldownSeconds": profile["cooldownSeconds"],
            "loadConfiguration": {key: profile[key] for key in (
                "arrivalRatePerSecond", "concurrency", "virtualUsers", "expectedScheduledOperations")
                                  if key in profile},
            "repetitionsExpected": manifest["repetitions"],
            "repetitionsExecuted": len(repetitions),
            "hardSafetyPassedAllRepetitions": hard_pass,
            "quantitativeVerdicts": quantitative,
            "manifestThresholdResults": manifest_threshold_results,
            "evidenceCompleteness": evidence_completeness,
            "scenarioAssertions": [item for value in repetitions for item in value.get("scenarioAssertions", [])],
            "repetitionEvidence": [{
                "repetition": value.get("repetition"),
                "configuredDurationSeconds": value.get("configuredDurationSeconds"),
                "actualMeasuredDurationSeconds": value.get("actualMeasuredDurationSeconds"),
                "fullDurationSatisfied": value.get("fullDurationSatisfied"),
                "scheduledOperations": value.get("scheduledOperations"),
                "completedOperations": value.get("completedOperations"),
                "successfulOperations": value.get("successfulOperations"),
                "droppedIterations": value.get("droppedIterations"),
                "baselineRunId": value.get("baselineRunId"),
                "baselineWorkloadHash": value.get("baselineWorkloadHash"),
                "metrics": value.get("metrics"),
                "thresholds": value.get("thresholds"),
                "passed": value.get("passed"),
                "failureReasons": value.get("failureReasons"),
            } for value in repetitions],
            "passed": hard_pass and not profile_failures,
            "failureReasons": sorted(set(profile_failures + [reason for value in repetitions for reason in value["failureReasons"]])),
            "evidencePaths": [str(path.relative_to(root)) for path in repetition_paths],
        }
        (root / profile["id"] / "result.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        profile_results.append(result)
        if not result["passed"]:
            failures.append(profile["id"])
        thresholds_failed.extend(reason for reason in result["failureReasons"] if "THRESHOLD" in reason)

    expected_scenarios = {value["id"] for value in manifest["mandatoryScenarios"]}
    asserted_scenarios = {assertion["scenarioId"] for result in profile_results for assertion in result["scenarioAssertions"]}
    missing_scenarios = sorted(expected_scenarios - asserted_scenarios)
    failed_scenario_assertions = sorted({
        assertion["scenarioId"] for result in profile_results
        for assertion in result["scenarioAssertions"] if not assertion.get("passed")
    })
    if missing_scenarios:
        failures.append("MISSING_SCENARIO_ASSERTIONS")
    if failed_scenario_assertions:
        failures.append("MANDATORY_SCENARIO_ASSERTIONS_FAILED")

    aggregate = {
        "runId": args.run_id,
        "repository": "sure-partner-observability",
        "gitCommit": args.git_commit,
        "mode": args.mode,
        "springProfile": "local",
        "startedAt": args.started_at,
        "endedAt": args.ended_at,
        "profilesExpected": len(manifest["profiles"]),
        "profilesExecuted": len(profile_results),
        "profilesPassed": [value["profileId"] for value in profile_results if value["passed"]],
        "profilesFailed": [value["profileId"] for value in profile_results if not value["passed"]],
        "fullDurationProfilesPassed": [value["profileId"] for value in profile_results if value["passed"]],
        "fullDurationProfilesFailed": [value["profileId"] for value in profile_results if not value["passed"]],
        "mandatoryScenariosExpected": sorted(expected_scenarios),
        "mandatoryScenariosAsserted": sorted(asserted_scenarios),
        "missingMandatoryScenarios": missing_scenarios,
        "failedMandatoryScenarioAssertions": failed_scenario_assertions,
        "thresholdsFailed": sorted(set(thresholds_failed)),
        "journeySeed": seed_result,
        "detailedEvidenceRoot": f"test/performance/evidence/{args.run_id}",
        "evidencePaths": [value["evidencePaths"] for value in profile_results],
        "overallPassed": args.mode == "full" and not failures and len(profile_results) == 9,
        "failureReasons": sorted(set(failures)),
    }
    (root / "aggregate-result.json").write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
