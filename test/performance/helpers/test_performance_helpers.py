#!/usr/bin/env python3
"""Focused unit tests for B003 evidence calculations."""

from __future__ import annotations

import json
import pathlib
import tempfile
import unittest

from datetime import datetime, timezone

from evaluate_repetition import allocation_samples, cpu_percent, gc_metrics, slope_per_minute


class PerformanceEvidenceCalculationTest(unittest.TestCase):

    def test_cpu_is_normalized_to_the_assigned_quota(self):
        self.assertEqual(50.0, cpu_percent("100.00%", 2.0))
        self.assertEqual(100.0, cpu_percent("200.00%", 2.0))
        self.assertIsNone(cpu_percent(None, 2.0))

    def test_heap_slope_is_reported_in_bytes_per_minute(self):
        points = [(0.0, 100.0), (60.0, 200.0), (120.0, 300.0)]
        self.assertAlmostEqual(100.0, slope_per_minute(points))

    def test_gc_deltas_are_derived_from_cumulative_prometheus_samples(self):
        labels = '{action="end of minor GC",cause="G1 Evacuation Pause",le="0.25"}'
        count_labels = '{action="end of minor GC",cause="G1 Evacuation Pause"}'
        measured = [
            {"jvm": {
                "jvm_gc_pause_seconds_count" + count_labels: 10.0,
                "jvm_gc_pause_seconds_sum" + count_labels: 1.0,
                "jvm_gc_pause_seconds_bucket" + labels: 10.0,
            }},
            {"jvm": {
                "jvm_gc_pause_seconds_count" + count_labels: 14.0,
                "jvm_gc_pause_seconds_sum" + count_labels: 1.4,
                "jvm_gc_pause_seconds_bucket" + labels: 14.0,
            }},
        ]

        result = gc_metrics(measured, "jvm")

        self.assertTrue(result["measured"])
        self.assertEqual(4.0, result["count"])
        self.assertAlmostEqual(400.0, result["pauseTotalMilliseconds"])
        self.assertEqual(250.0, result["pauseP95Milliseconds"])

    def test_machine_readable_fixture_round_trip_does_not_need_payloads(self):
        evidence = {
            "runId": "SYNTHETIC-RUN",
            "profileId": "metadata",
            "metrics": {"successfulOperations": 100000, "rawPayloadsExposed": False},
            "passed": True,
        }
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "result.json"
            path.write_text(json.dumps(evidence), encoding="utf-8")
            self.assertEqual(evidence, json.loads(path.read_text(encoding="utf-8")))

    def test_jfr_allocation_samples_are_bounded_to_the_measured_interval(self):
        lines = [
            '"startTime": "2026-08-30T00:00:00Z",', '"weight": 100',
            '"startTime": "2026-08-30T00:05:00Z",', '"weight": 250',
            '"startTime": "2026-08-30T00:20:00Z",', '"weight": 500',
        ]
        start = datetime(2026, 8, 30, 0, 1, tzinfo=timezone.utc)
        end = datetime(2026, 8, 30, 0, 10, tzinfo=timezone.utc)

        self.assertEqual((250, 1), allocation_samples(lines, start, end))


if __name__ == "__main__":
    unittest.main()
