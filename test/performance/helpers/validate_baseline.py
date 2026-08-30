#!/usr/bin/env python3
"""Validate Q015-A environment stability across a three-run matched baseline set."""

from __future__ import annotations

import argparse
import json
import pathlib
import statistics


def coefficient(values: list[float]) -> float | None:
    if len(values) != 3:
        return None
    mean = statistics.fmean(values)
    return 0.0 if mean == 0 else statistics.pstdev(values) / mean * 100.0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("results", nargs="+")
    args = parser.parse_args()
    manifest = json.loads(pathlib.Path(args.manifest).read_text(encoding="utf-8"))
    results = [json.loads(pathlib.Path(path).read_text(encoding="utf-8")) for path in args.results]
    definitions = {
        "p95Milliseconds": "p95",
        "p99Milliseconds": "p99",
        "normalizedCpuMean": "normalizedMeanCpu",
        "heapPeakBytes": "peakHeap",
    }
    checks = []
    failures = []
    hard_results_valid = len(results) == 3 and all(
        result.get("passed") is True and result.get("isMatchedBaseline") is True for result in results)
    hashes = {result.get("baselineWorkloadHash") for result in results}
    commits = {result.get("gitCommit") for result in results}
    environments = {result.get("environmentFingerprint") for result in results}
    identity_valid = len(hashes) == 1 and None not in hashes and len(commits) == 1 and None not in commits \
        and len(environments) == 1 and None not in environments
    checks.append({"metric": "baselineHardSafety", "measured": len(results) == 3,
                   "passed": hard_results_valid})
    checks.append({"metric": "baselineIdentity", "measured": len(results) == 3,
                   "passed": identity_valid})
    if not hard_results_valid:
        failures.append("BASELINE_HARD_SAFETY_FAILED")
    if not identity_valid:
        failures.append("BASELINE_IDENTITY_MISMATCH")
    for metric, limit_name in definitions.items():
        values = [result["metrics"].get(metric) for result in results]
        measured = len(values) == 3 and all(value is not None for value in values)
        value = coefficient(values) if measured else None
        limit = manifest["comparison"]["baselineMaximumCoefficientOfVariationPercent"][limit_name]
        passed = measured and value <= limit
        checks.append({"metric": metric, "values": values, "coefficientOfVariationPercent": value,
                       "limitPercent": limit, "measured": measured, "passed": passed})
        if not passed:
            failures.append(("BASELINE_NOT_MEASURED:" if not measured else "BASELINE_UNSTABLE:") + metric)
    result = {
        "baselineRepetitionsExpected": 3,
        "baselineRepetitionsExecuted": len(results),
        "checks": checks,
        "passed": not failures,
        "verdict": "STABLE" if not failures else "INCONCLUSIVE_ENVIRONMENT_UNSTABLE",
        "failureReasons": failures,
    }
    pathlib.Path(args.output).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    raise SystemExit(0 if result["passed"] else 2)


if __name__ == "__main__":
    main()
