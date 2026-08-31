#!/usr/bin/env python3

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
GENERATOR = ROOT / "test/partner-contracts/tools/openapi_fixture_generator.py"
CAPABILITIES = ROOT / "test/partner-contracts/generic-capabilities.json"


OPENAPI = """\
openapi: 3.0.3
info:
  title: Synthetic selected contract
  version: '1'
servers:
  - url: https://should-not-be-retained.invalid
paths:
  /split:
    $ref: './split-path.yaml#/splitPath'
  /applications:
    post:
      operationId: submitApplication
      security:
        - partnerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                applicationId: {type: string, example: REAL-LIKE-VALUE-MUST-NOT-BE-RETAINED}
                password: {type: string, example: SECRET-MUST-NOT-BE-RETAINED}
                documentBase64: {type: string, format: byte}
      responses:
        '202': {description: accepted}
      callbacks:
        result:
          '{$request.body#/callbackUrl}':
            post:
              operationId: receiveResult
              requestBody:
                content:
                  application/json:
                    schema:
                      type: object
                      properties:
                        callbackReferenceId: {type: string}
              responses:
                '200': {description: ok}
components:
  securitySchemes:
    partnerAuth:
      type: apiKey
      in: header
      name: Authorization
"""

SPLIT_PATH = """\
splitPath:
  get:
    operationId: splitOperation
    responses:
      '200':
        description: ok
        content:
          application/json:
            schema:
              type: object
              properties:
                loanId: {type: string}
"""


class GeneratorTest(unittest.TestCase):
    def test_selected_service_only_and_fail_closed_coverage(self):
        with tempfile.TemporaryDirectory(prefix="target-service-generator-") as raw:
            workspace = Path(raw)
            selected = workspace / "sure-nbfc-selected"
            foreign = workspace / "sure-nbfc-foreign"
            (selected / "contracts").mkdir(parents=True)
            (foreign / "contracts").mkdir(parents=True)
            (selected / "contracts/api.yaml").write_text(OPENAPI, encoding="utf-8")
            (selected / "contracts/split-path.yaml").write_text(SPLIT_PATH, encoding="utf-8")
            (foreign / "contracts/foreign.yaml").write_text(
                OPENAPI.replace("submitApplication", "foreignOperation"), encoding="utf-8"
            )
            output = workspace / "generated"
            command = [
                "python3", str(GENERATOR),
                "--service-name", "sure-nbfc-selected",
                "--service-root", str(selected),
                "--output-root", str(output),
                "--capabilities", str(CAPABILITIES),
            ]
            first = subprocess.run(command, text=True, capture_output=True, check=False)
            self.assertEqual(4, first.returncode, first.stderr)
            generated = output / "sure-nbfc-selected"
            inventory = json.loads((generated / "contract-inventory.json").read_text(encoding="utf-8"))
            coverage = json.loads((generated / "coverage.json").read_text(encoding="utf-8"))
            fixture_manifest = json.loads((generated / "fixture-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("sure-nbfc-selected", inventory["service"])
            self.assertEqual(3, len(inventory["operations"]))
            self.assertEqual("contracts/split-path.yaml", inventory["referencedSources"][0]["path"])
            self.assertTrue(all(item["status"] == "NOT_COVERED" for item in coverage["coverage"]))
            serialized = json.dumps(inventory)
            self.assertNotIn("should-not-be-retained", serialized)
            self.assertNotIn("REAL-LIKE-VALUE", serialized)
            self.assertNotIn("SECRET-MUST", serialized)
            self.assertNotIn("foreignOperation", serialized)
            fixture_serialized = json.dumps(fixture_manifest)
            self.assertIn("GENERATE_SYNTHETIC_BINARY_IN_MEMORY_AND_ASSERT_PRE_QUEUE_OMISSION", fixture_serialized)
            self.assertIn("USE_SYNTHETIC_REMOVAL_SENTINEL_AND_ASSERT_ABSENT", fixture_serialized)
            self.assertNotIn("SECRET-MUST", fixture_serialized)
            self.assertFalse((output / "sure-nbfc-foreign").exists())

            mapping = {
                "schemaVersion": 1,
                "service": "sure-nbfc-selected",
                "operations": {},
            }
            for operation in inventory["operations"]:
                mapping["operations"][operation["key"]] = {
                    "direction": "INBOUND" if operation["contractRole"] == "CALLBACK" else "OUTBOUND",
                    "interactionPattern": "ASYNC_HTTP_202_CALLBACK" if operation["contractRole"] != "CALLBACK" else "SYNC_JSON",
                    "observabilityMechanism": "STARTER_CONFIGURATION",
                    "testScenario": "synthetic-async-journey",
                    "status": "COVERED_BY_GENERIC_FIXTURE",
                    "correlationMappings": {},
                    "justification": "Synthetic reviewed test mapping.",
                }
            mapping_path = workspace / "coverage.json"
            mapping_path.write_text(json.dumps(mapping), encoding="utf-8")
            second = subprocess.run(command + ["--mapping", str(mapping_path)], text=True, capture_output=True, check=False)
            self.assertEqual(0, second.returncode, second.stderr)
            ready = json.loads((generated / "coverage.json").read_text(encoding="utf-8"))
            self.assertTrue(ready["ready"])
            self.assertEqual([], ready["notCovered"])

    def test_rejects_root_name_mismatch(self):
        with tempfile.TemporaryDirectory(prefix="target-service-mismatch-") as raw:
            root = Path(raw) / "sure-nbfc-actual"
            root.mkdir()
            result = subprocess.run([
                "python3", str(GENERATOR),
                "--service-name", "sure-nbfc-requested",
                "--service-root", str(root),
                "--output-root", str(Path(raw) / "out"),
                "--capabilities", str(CAPABILITIES),
            ], text=True, capture_output=True, check=False)
            self.assertEqual(2, result.returncode)
            self.assertIn("basename does not match", result.stderr)

    def test_rejects_remote_schema_reference_instead_of_fetching_it(self):
        with tempfile.TemporaryDirectory(prefix="target-service-remote-ref-") as raw:
            root = Path(raw) / "sure-nbfc-selected"
            root.mkdir()
            (root / "api.yaml").write_text("""\
openapi: 3.0.3
info: {title: remote, version: '1'}
paths:
  /remote:
    post:
      requestBody:
        content:
          application/json:
            schema: {$ref: 'https://external.invalid/schema.yaml'}
      responses:
        '200': {description: ok}
""", encoding="utf-8")
            result = subprocess.run([
                "python3", str(GENERATOR),
                "--service-name", "sure-nbfc-selected",
                "--service-root", str(root),
                "--output-root", str(Path(raw) / "out"),
                "--capabilities", str(CAPABILITIES),
            ], text=True, capture_output=True, check=False)
            self.assertEqual(2, result.returncode)
            self.assertIn("remote OpenAPI references are not fetched", result.stderr)


if __name__ == "__main__":
    unittest.main()
