#!/usr/bin/env python3
"""Collect bounded payload-free JVM and container samples for one B003 repetition."""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import time
import urllib.request


def read_json(url: str) -> dict | None:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return json.load(response)
    except Exception:
        return None


def prometheus(url: str) -> dict:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            body = response.read().decode("utf-8", "replace")
    except Exception:
        return {}
    result: dict[str, float] = {}
    for line in body.splitlines():
        if line.startswith("#"):
            continue
        match = re.match(r'^(jvm_gc_pause_seconds_(?:count|sum|bucket))(\{[^}]*\})?\s+([0-9.eE+-]+)$', line)
        if not match:
            continue
        name, labels, raw = match.groups()
        key = name + (labels or "")
        try:
            result[key] = float(raw)
        except ValueError:
            pass
    return result


def docker_stats(container: str) -> dict | None:
    completed = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{json .}}", container],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        timeout=5,
        check=False,
    )
    if completed.returncode != 0 or not completed.stdout.strip():
        return None
    try:
        value = json.loads(completed.stdout)
        value.pop("Name", None)
        value.pop("Container", None)
        return value
    except json.JSONDecodeError:
        return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--app-url", required=True)
    parser.add_argument("--reactive-url", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--interval", type=float, default=5.0)
    parser.add_argument("--stop-file", required=True)
    args = parser.parse_args()

    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    stop = pathlib.Path(args.stop_file)
    test_app = f"{args.project}-test-app-1"
    reactive_app = f"{args.project}-reactive-test-app-1"
    started = time.monotonic()
    sample = 0
    with output.open("a", encoding="utf-8") as target:
        while not stop.exists():
            now = time.time()
            value = {
                "sample": sample,
                "timestampEpochSeconds": now,
                "elapsedSeconds": time.monotonic() - started,
                "jvm": read_json(args.app_url + "/fixture/performance/jvm"),
                "reactiveJvm": read_json(args.reactive_url + "/fixture/reactive/jvm"),
                "telemetry": read_json(args.app_url + "/fixture/performance/health"),
                "prometheusJvmGc": prometheus(args.app_url + "/actuator/prometheus"),
                "reactivePrometheusJvmGc": prometheus(args.reactive_url + "/actuator/prometheus"),
                "containers": {
                    "test-app": docker_stats(test_app),
                    "reactive-test-app": docker_stats(reactive_app),
                },
            }
            target.write(json.dumps(value, separators=(",", ":")) + "\n")
            target.flush()
            sample += 1
            stop_at = time.monotonic() + args.interval
            while not stop.exists() and time.monotonic() < stop_at:
                time.sleep(min(0.25, max(0.0, stop_at - time.monotonic())))


if __name__ == "__main__":
    main()
