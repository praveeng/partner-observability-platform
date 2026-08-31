#!/usr/bin/env python3
"""Generate fail-closed, structural fixtures from one explicitly selected service.

The generator deliberately does not retain examples, defaults, server URLs, payloads, or security
values. It never infers partner-specific correlation semantics from suggestive field names.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

import yaml


HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
IGNORED_DIRECTORIES = {".git", ".gradle", ".idea", "build", "node_modules", "out", "target"}
ALLOWED_STATUSES = {
    "COVERED_BY_GENERIC_FIXTURE",
    "COVERED_BY_GENERATED_FIXTURE",
    "REQUIRES_GENERIC_CAPABILITY",
    "EXPLICITLY_EXCLUDED",
    "NOT_COVERED",
}
MAPPING_FIELDS = {
    "direction",
    "interactionPattern",
    "observabilityMechanism",
    "testScenario",
    "status",
    "correlationMappings",
    "justification",
}
CORRELATION_NAMES = {
    "applicationid": "applicationId",
    "loanid": "loanId",
    "correlationid": "correlationId",
    "originalcorrelationid": "originalCorrelationId",
    "requestid": "requestId",
    "partnerreferenceid": "partnerReferenceId",
    "callbackreferenceid": "callbackReferenceId",
    "externaltransactionid": "externalTransactionId",
}
SECRET_TERMS = ("password", "secret", "token", "authorization", "apikey", "credential", "privatekey", "otp", "cvv")
PII_TERMS = ("phone", "mobile", "email", "accountnumber", "nationalid", "address", "firstname", "lastname")
BINARY_TERMS = ("base64", "document", "image", "photo", "pdf", "signature", "attachment", "filecontent", "blob")
ENCRYPTION_TERMS = ("ciphertext", "encryptedpayload", "encryptedrequest", "encryptedresponse", "iv", "nonce", "authTag")


class ContractError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--service-name", required=True)
    parser.add_argument("--service-root", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--capabilities", required=True, type=Path)
    parser.add_argument("--mapping", type=Path)
    return parser.parse_args()


def normalized_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def json_write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def yaml_candidates(root: Path) -> Iterable[Path]:
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in {".yaml", ".yml"}:
            continue
        if any(part in IGNORED_DIRECTORIES for part in path.relative_to(root).parts[:-1]):
            continue
        yield path


def load_openapi_documents(root: Path) -> tuple[list[tuple[Path, dict[str, Any]]], list[dict[str, str]]]:
    documents: list[tuple[Path, dict[str, Any]]] = []
    ignored: list[dict[str, str]] = []
    for path in yaml_candidates(root):
        if path.stat().st_size > 32 * 1024 * 1024:
            ignored.append({"path": path.relative_to(root).as_posix(), "reason": "YAML_SIZE_LIMIT"})
            continue
        try:
            loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, yaml.YAMLError):
            ignored.append({"path": path.relative_to(root).as_posix(), "reason": "UNREADABLE_OR_INVALID_YAML"})
            continue
        if isinstance(loaded, dict) and (isinstance(loaded.get("openapi"), str) or isinstance(loaded.get("swagger"), str)):
            documents.append((path, loaded))
        else:
            ignored.append({"path": path.relative_to(root).as_posix(), "reason": "NOT_OPENAPI"})
    return documents, ignored


def load_yaml_mapping(path: Path, cache: dict[Path, dict[str, Any]]) -> dict[str, Any]:
    canonical = path.resolve(strict=True)
    if canonical in cache:
        return cache[canonical]
    if canonical.stat().st_size > 32 * 1024 * 1024:
        raise ContractError(f"referenced YAML exceeds size limit: {canonical.name}")
    try:
        loaded = yaml.safe_load(canonical.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, yaml.YAMLError) as error:
        raise ContractError(f"referenced YAML is unreadable or invalid: {canonical.name}") from error
    if not isinstance(loaded, dict):
        raise ContractError(f"referenced YAML root is not an object: {canonical.name}")
    cache[canonical] = loaded
    return loaded


def pointer_value(document: Any, fragment: str) -> Any:
    if fragment in {"", "#"}:
        return document
    if not fragment.startswith("#/"):
        raise ContractError("only local JSON-pointer OpenAPI fragments are supported")
    value = document
    for raw_token in fragment[2:].split("/"):
        token = raw_token.replace("~1", "/").replace("~0", "~")
        if not isinstance(value, dict) or token not in value:
            raise ContractError("OpenAPI reference points to a missing object")
        value = value[token]
    return value


def expand_local_refs(
    value: Any,
    current_file: Path,
    root: Path,
    cache: dict[Path, dict[str, Any]],
    referenced: set[Path],
    stack: frozenset[str] = frozenset(),
    depth: int = 0,
) -> Any:
    if depth > 64:
        raise ContractError("OpenAPI reference expansion exceeded the bounded depth")
    if isinstance(value, list):
        return [expand_local_refs(item, current_file, root, cache, referenced, stack, depth + 1) for item in value]
    if not isinstance(value, dict):
        return value
    reference = value.get("$ref")
    if isinstance(reference, str):
        if "://" in reference:
            raise ContractError("remote OpenAPI references are not fetched; vendor the reviewed schema in the selected service")
        file_part, separator, fragment_part = reference.partition("#")
        target_file = current_file if not file_part else (current_file.parent / file_part).resolve()
        try:
            target_file.relative_to(root)
        except ValueError as error:
            raise ContractError("OpenAPI reference escapes the selected target service") from error
        target_document = load_yaml_mapping(target_file, cache)
        if target_file != current_file:
            referenced.add(target_file)
        fragment = "#" + fragment_part if separator else ""
        marker = f"{target_file}:{fragment}"
        if marker in stack:
            return {"$ref": reference}
        target_value = pointer_value(target_document, fragment)
        expanded = expand_local_refs(
            target_value, target_file, root, cache, referenced, stack | {marker}, depth + 1
        )
        if not isinstance(expanded, dict):
            return expanded
        siblings = {
            key: expand_local_refs(item, current_file, root, cache, referenced, stack, depth + 1)
            for key, item in value.items()
            if key != "$ref"
        }
        return {**expanded, **siblings}
    return {
        key: expand_local_refs(item, current_file, root, cache, referenced, stack, depth + 1)
        for key, item in value.items()
    }


def ref_name(schema: Any) -> str | None:
    if isinstance(schema, dict) and isinstance(schema.get("$ref"), str):
        return schema["$ref"].rsplit("/", 1)[-1]
    return None


def schema_summary(schema: Any) -> dict[str, Any]:
    if not isinstance(schema, dict):
        return {"kind": "UNSPECIFIED"}
    result: dict[str, Any] = {}
    if ref_name(schema):
        result["ref"] = ref_name(schema)
    for key in ("type", "format", "nullable"):
        if key in schema and isinstance(schema[key], (str, bool)):
            result[key] = schema[key]
    if isinstance(schema.get("required"), list):
        result["requiredFields"] = sorted(str(item) for item in schema["required"])
    if isinstance(schema.get("properties"), dict):
        result["fields"] = sorted(str(item) for item in schema["properties"])
    if isinstance(schema.get("items"), dict):
        result["items"] = schema_summary(schema["items"])
    for composition in ("allOf", "anyOf", "oneOf"):
        if isinstance(schema.get(composition), list):
            result[composition] = [schema_summary(item) for item in schema[composition]]
    return result or {"kind": "UNSPECIFIED"}


def content_summary(content: Any) -> list[dict[str, Any]]:
    if not isinstance(content, dict):
        return []
    return [
        {"mediaType": str(media_type).lower(), "schema": schema_summary(media.get("schema"))}
        for media_type, media in sorted(content.items())
        if isinstance(media, dict)
    ]


def security_summary(security: Any) -> list[list[str]]:
    if not isinstance(security, list):
        return []
    result: list[list[str]] = []
    for requirement in security:
        if isinstance(requirement, dict):
            result.append(sorted(str(key) for key in requirement))
    return result


def classify_field(path: str, schema: dict[str, Any]) -> list[str]:
    name = normalized_name(path.rsplit(".", 1)[-1])
    classifications: list[str] = []
    schema_format = str(schema.get("format", "")).lower()
    schema_type = str(schema.get("type", "")).lower()
    if schema_format in {"binary", "byte", "base64"} or any(term in name for term in BINARY_TERMS):
        classifications.append("BINARY_OR_BASE64_CANDIDATE")
    if any(term in name for term in SECRET_TERMS):
        classifications.append("REMOVE_CANDIDATE")
    if any(term in name for term in PII_TERMS):
        classifications.append("MASK_CANDIDATE")
    if name in CORRELATION_NAMES:
        classifications.append("CORRELATION_CANDIDATE_" + CORRELATION_NAMES[name])
    if any(normalized_name(term) in name for term in ENCRYPTION_TERMS):
        classifications.append("ENCRYPTION_RELEVANT_CANDIDATE")
    if schema_type == "array":
        classifications.append("ARRAY")
    return sorted(set(classifications))


def walk_schema(schema: Any, prefix: str, depth: int = 0) -> list[dict[str, Any]]:
    if not isinstance(schema, dict) or depth > 24:
        return []
    fields: list[dict[str, Any]] = []
    properties = schema.get("properties")
    if isinstance(properties, dict):
        for name, child in sorted(properties.items()):
            if not isinstance(child, dict):
                continue
            path = f"{prefix}.{name}" if prefix else str(name)
            classifications = classify_field(path, child)
            fields.append({
                "path": path,
                "type": child.get("type"),
                "format": child.get("format"),
                "classifications": classifications,
            })
            fields.extend(walk_schema(child, path, depth + 1))
    items = schema.get("items")
    if isinstance(items, dict):
        fields.extend(walk_schema(items, prefix + "[]", depth + 1))
    for composition in ("allOf", "anyOf", "oneOf"):
        values = schema.get(composition)
        if isinstance(values, list):
            for index, child in enumerate(values):
                fields.extend(walk_schema(child, f"{prefix}.{composition}[{index}]", depth + 1))
    return fields


def response_summary(responses: Any) -> list[dict[str, Any]]:
    if not isinstance(responses, dict):
        return []
    return [
        {"status": str(status), "content": content_summary(response.get("content"))}
        for status, response in sorted(responses.items(), key=lambda item: str(item[0]))
        if isinstance(response, dict)
    ]


def pattern_candidates(operation: dict[str, Any]) -> list[str]:
    patterns: set[str] = set()
    media_types = {
        item["mediaType"]
        for item in operation["requestContent"]
    }
    media_types.update(
        item["mediaType"]
        for response in operation["responses"]
        for item in response["content"]
    )
    classifications = {
        classification
        for field in operation["classifiedFields"]
        for classification in field["classifications"]
    }
    correlation_candidates = {
        value.removeprefix("CORRELATION_CANDIDATE_")
        for value in classifications
        if value.startswith("CORRELATION_CANDIDATE_")
    }
    response_statuses = {response["status"] for response in operation["responses"]}
    if operation["contractRole"] in {"CALLBACK", "WEBHOOK"}:
        patterns.add("WEBFLUX_CALLBACK" if "text/event-stream" in media_types else "SYNC_JSON")
        if "partnerReferenceId" in correlation_candidates and "applicationId" not in correlation_candidates:
            patterns.add("CALLBACK_PARTNER_REFERENCE_ONLY")
    if operation["callbackCount"] > 0 or "202" in response_statuses:
        patterns.add("ASYNC_HTTP_202_CALLBACK")
    if operation["callbackCount"] > 1:
        patterns.add("CALLBACK_MULTIPLE_PER_REQUEST")
    if any(media_type.startswith("multipart/") for media_type in media_types):
        patterns.add("MULTIPART_BINARY_OMISSION")
    if "BINARY_OR_BASE64_CANDIDATE" in classifications:
        patterns.add("NESTED_BINARY_OMISSION")
    if "ENCRYPTION_RELEVANT_CANDIDATE" in classifications:
        patterns.add("ENCRYPTED_LOGICAL_PAYLOAD")
    if any(status.startswith(("4", "5")) or status == "default" for status in response_statuses):
        patterns.add("REST_JSON_ERROR")
    if any(media_type == "text/event-stream" for media_type in media_types):
        patterns.add("WEBCLIENT_REACTIVE")
    if any("json" in media_type for media_type in media_types) or not media_types:
        patterns.add("SYNC_JSON")
    normal_media = {
        "application/json", "application/problem+json", "application/octet-stream",
        "application/pdf", "image/jpeg", "image/png", "multipart/form-data",
        "text/event-stream",
    }
    if any(media_type not in normal_media and not media_type.endswith("+json") for media_type in media_types):
        patterns.add("UNUSUAL_CONTENT_TYPE")
    return sorted(patterns)


def fixture_strategy(field: dict[str, Any]) -> str:
    classifications = set(field["classifications"])
    if "BINARY_OR_BASE64_CANDIDATE" in classifications:
        return "GENERATE_SYNTHETIC_BINARY_IN_MEMORY_AND_ASSERT_PRE_QUEUE_OMISSION"
    if "REMOVE_CANDIDATE" in classifications:
        return "USE_SYNTHETIC_REMOVAL_SENTINEL_AND_ASSERT_ABSENT"
    if "MASK_CANDIDATE" in classifications:
        return "USE_SYNTHETIC_MASK_SENTINEL_AND_ASSERT_MASKED"
    if any(value.startswith("CORRELATION_CANDIDATE_") for value in classifications):
        return "USE_SYNTHETIC_CORRELATION_IDENTIFIER_AFTER_SEMANTIC_MAPPING_APPROVAL"
    if "ENCRYPTION_RELEVANT_CANDIDATE" in classifications:
        return "USE_GENERIC_LOGICAL_PAYLOAD_ENCRYPTION_FIXTURE_IF_CONFIRMED"
    return "USE_SCHEMA_CONFORMING_SYNTHETIC_VALUE"


def callback_operations(spec_path: str, parent_key: str, operation: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    callbacks = operation.get("callbacks")
    if not isinstance(callbacks, dict):
        return result
    for callback_name, callback_value in sorted(callbacks.items()):
        if not isinstance(callback_value, dict):
            continue
        for expression, path_item in sorted(callback_value.items()):
            if expression == "$ref" or not isinstance(path_item, dict):
                continue
            for method, callback_operation in sorted(path_item.items()):
                if method.lower() not in HTTP_METHODS or not isinstance(callback_operation, dict):
                    continue
                synthetic_path = f"callback:{callback_name}:{expression}"
                result.append(build_operation(spec_path, synthetic_path, method, callback_operation, "CALLBACK", parent_key))
    return result


def build_operation(
    spec_path: str,
    path: str,
    method: str,
    operation: dict[str, Any],
    role: str,
    parent_operation: str | None = None,
) -> dict[str, Any]:
    request_body = operation.get("requestBody") if isinstance(operation.get("requestBody"), dict) else {}
    request_content = content_summary(request_body.get("content"))
    parameters: list[dict[str, Any]] = []
    for parameter in operation.get("parameters", []) if isinstance(operation.get("parameters"), list) else []:
        if isinstance(parameter, dict):
            parameters.append({
                "name": parameter.get("name"),
                "in": parameter.get("in"),
                "required": bool(parameter.get("required", False)),
                "schema": schema_summary(parameter.get("schema")),
            })
    classified_fields: list[dict[str, Any]] = []
    raw_request_content = request_body.get("content", {})
    if isinstance(raw_request_content, dict):
        for media in raw_request_content.values():
            if isinstance(media, dict):
                classified_fields.extend(walk_schema(media.get("schema"), "request"))
    for parameter in operation.get("parameters", []) if isinstance(operation.get("parameters"), list) else []:
        if isinstance(parameter, dict) and isinstance(parameter.get("schema"), dict):
            classified_fields.extend(walk_schema(parameter["schema"], f"parameter.{parameter.get('name', 'unnamed')}"))
    operation_key = f"{spec_path}#{method.upper()}#{path}"
    result = {
        "key": operation_key,
        "spec": spec_path,
        "path": path,
        "method": method.upper(),
        "operationId": operation.get("operationId"),
        "contractRole": role,
        "parentOperation": parent_operation,
        "requestContent": request_content,
        "parameters": parameters,
        "responses": response_summary(operation.get("responses")),
        "securityRequirements": security_summary(operation.get("security")),
        "callbackCount": len(operation.get("callbacks", {})) if isinstance(operation.get("callbacks"), dict) else 0,
        "classifiedFields": sorted(classified_fields, key=lambda item: item["path"]),
    }
    result["patternCandidates"] = pattern_candidates(result)
    return result


def inventory_document(root: Path, path: Path, document: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    relative = path.relative_to(root).as_posix()
    operations: list[dict[str, Any]] = []
    paths = document.get("paths")
    if isinstance(paths, dict):
        for api_path, path_item in sorted(paths.items()):
            if not isinstance(path_item, dict):
                continue
            for method, operation in sorted(path_item.items()):
                if method.lower() not in HTTP_METHODS or not isinstance(operation, dict):
                    continue
                built = build_operation(relative, str(api_path), method, operation, "SERVICE_OPERATION")
                operations.append(built)
                operations.extend(callback_operations(relative, built["key"], operation))
    webhooks = document.get("webhooks")
    if isinstance(webhooks, dict):
        for webhook_name, path_item in sorted(webhooks.items()):
            if not isinstance(path_item, dict):
                continue
            for method, operation in sorted(path_item.items()):
                if method.lower() in HTTP_METHODS and isinstance(operation, dict):
                    operations.append(build_operation(relative, f"webhook:{webhook_name}", method, operation, "WEBHOOK"))

    component_schemas = document.get("components", {}).get("schemas", {}) if isinstance(document.get("components"), dict) else {}
    classified_schemas = []
    if isinstance(component_schemas, dict):
        for name, schema in sorted(component_schemas.items()):
            fields = walk_schema(schema, str(name))
            classified_schemas.append({"name": str(name), "summary": schema_summary(schema), "classifiedFields": fields})

    security_schemes = document.get("components", {}).get("securitySchemes", {}) if isinstance(document.get("components"), dict) else {}
    security_inventory = []
    if isinstance(security_schemes, dict):
        for name, scheme in sorted(security_schemes.items()):
            if not isinstance(scheme, dict):
                continue
            security_inventory.append({
                "name": str(name),
                "type": scheme.get("type"),
                "scheme": scheme.get("scheme"),
                "in": scheme.get("in"),
                "parameterName": scheme.get("name"),
            })
    source = {
        "path": relative,
        "sha256": sha256(path),
        "openapiVersion": document.get("openapi") or document.get("swagger"),
        "operationCount": len(operations),
        "securitySchemes": security_inventory,
        "serverSchemes": sorted({
            str(server.get("url", "")).split(":", 1)[0].lower()
            for server in document.get("servers", []) if isinstance(server, dict) and ":" in str(server.get("url", ""))
        }),
        "classifiedSchemas": classified_schemas,
    }
    return source, operations


def generated_boundary(root: Path) -> dict[str, Any]:
    build_files = [path for path in (root / "build.gradle", root / "settings.gradle", root / "build.gradle.kts", root / "settings.gradle.kts") if path.is_file()]
    evidence: set[str] = set()
    for path in build_files:
        text = path.read_text(encoding="utf-8", errors="replace").lower()
        for marker in ("openapi-generator", "openapigenerator", "swagger-codegen", "generated-src", "src/main/generated"):
            if marker in text:
                evidence.add(marker)
    java_files = [path for path in root.rglob("*.java") if not any(part in {".git", ".gradle", "build"} for part in path.relative_to(root).parts)]
    generated = [path for path in java_files if any(part.lower() in {"generated", "generated-sources", "openapi"} for part in path.relative_to(root).parts)]
    return {
        "buildMarkers": sorted(evidence),
        "javaSourceCount": len(java_files),
        "generatedPathJavaCount": len(generated),
        "rule": "Generated OpenAPI source is inventory-only and must not be modified by this process.",
    }


def load_mapping(path: Path | None, service: str) -> dict[str, Any]:
    if path is None:
        return {"schemaVersion": 1, "service": service, "operations": {}}
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("service") != service or not isinstance(value.get("operations"), dict):
        raise ContractError("coverage mapping must name the exact selected service and contain an operations object")
    return value


def coverage_for(operations: list[dict[str, Any]], mapping: dict[str, Any], capabilities: set[str]) -> list[dict[str, Any]]:
    actual_keys = {operation["key"] for operation in operations}
    extra = sorted(set(mapping["operations"]) - actual_keys)
    if extra:
        raise ContractError("coverage mapping contains stale or foreign operation keys: " + ", ".join(extra))
    results = []
    for operation in operations:
        decision = mapping["operations"].get(operation["key"], {})
        unknown_fields = sorted(set(decision) - MAPPING_FIELDS) if isinstance(decision, dict) else []
        if unknown_fields:
            raise ContractError(f"mapping {operation['key']} has unsupported fields: {', '.join(unknown_fields)}")
        status = decision.get("status", "NOT_COVERED") if isinstance(decision, dict) else "NOT_COVERED"
        if status not in ALLOWED_STATUSES:
            raise ContractError(f"mapping {operation['key']} has unsupported status {status}")
        if status == "EXPLICITLY_EXCLUDED" and not str(decision.get("justification", "")).strip():
            raise ContractError(f"mapping {operation['key']} exclusion requires justification")
        pattern = decision.get("interactionPattern") if isinstance(decision, dict) else None
        if status == "COVERED_BY_GENERIC_FIXTURE" and pattern not in capabilities:
            raise ContractError(f"mapping {operation['key']} references an unknown generic capability")
        results.append({
            "operationKey": operation["key"],
            "operationId": operation["operationId"],
            "contractRole": operation["contractRole"],
            "direction": decision.get("direction", "UNRESOLVED"),
            "interactionPattern": pattern,
            "observabilityMechanism": decision.get("observabilityMechanism"),
            "testScenario": decision.get("testScenario"),
            "correlationMappings": decision.get("correlationMappings", {}),
            "status": status,
            "justification": decision.get("justification"),
            "patternCandidates": operation["patternCandidates"],
        })
    return results


def main() -> int:
    args = parse_args()
    if not re.fullmatch(r"sure-nbfc-[a-z0-9][a-z0-9-]*", args.service_name):
        raise ContractError("service name is not an exact sure-nbfc-* basename")
    service_root = args.service_root.resolve(strict=True)
    if service_root.name != args.service_name:
        raise ContractError("service root basename does not match selected service")
    capabilities_value = json.loads(args.capabilities.read_text(encoding="utf-8"))
    capabilities = set(capabilities_value.get("capabilities", []))
    documents, ignored = load_openapi_documents(service_root)
    reference_cache = {path.resolve(): document for path, document in documents}
    referenced_sources: set[Path] = set()
    output_dir = args.output_root.resolve() / args.service_name
    sources: list[dict[str, Any]] = []
    operations: list[dict[str, Any]] = []
    for path, document in documents:
        expanded_document = expand_local_refs(
            document, path.resolve(), service_root, reference_cache, referenced_sources
        )
        source, source_operations = inventory_document(service_root, path, expanded_document)
        sources.append(source)
        operations.extend(source_operations)
    operations.sort(key=lambda item: item["key"])
    if len({item["key"] for item in operations}) != len(operations):
        raise ContractError("duplicate generated operation keys")

    mapping = load_mapping(args.mapping, args.service_name)
    coverage = coverage_for(operations, mapping, capabilities)
    status_counts = Counter(item["status"] for item in coverage)
    uncovered = [item["operationKey"] for item in coverage if item["status"] == "NOT_COVERED"]
    scenario_manifest = [
        {
            "scenario": item["testScenario"],
            "operationKey": item["operationKey"],
            "interactionPattern": item["interactionPattern"],
            "status": item["status"],
        }
        for item in coverage
        if item["testScenario"] is not None
    ]
    classified_schemas = [
        {"spec": source["path"], **schema}
        for source in sources
        for schema in source.pop("classifiedSchemas")
    ]
    patterns = [
        {
            "operationKey": operation["key"],
            "contractRole": operation["contractRole"],
            "candidates": operation["patternCandidates"],
            "matchedGenericCapabilities": sorted(set(operation["patternCandidates"]) & capabilities),
            "genericCapabilityGaps": sorted(set(operation["patternCandidates"]) - capabilities),
            "requiresSemanticReview": True,
        }
        for operation in operations
    ]
    fixture_recipes = [
        {
            "operationKey": operation["key"],
            "operationId": operation["operationId"],
            "contractRole": operation["contractRole"],
            "contentTypes": sorted({item["mediaType"] for item in operation["requestContent"]}),
            "fieldRecipes": [
                {
                    "path": field["path"],
                    "type": field["type"],
                    "format": field["format"],
                    "classifications": field["classifications"],
                    "valueStrategy": fixture_strategy(field),
                }
                for field in operation["classifiedFields"]
            ],
            "notice": "Recipe only: construct schema-valid values in the selected service adapter; no source or payload is generated here.",
        }
        for operation in operations
    ]

    referenced_paths = {path.relative_to(service_root).as_posix() for path in referenced_sources}
    ignored = [item for item in ignored if item["path"] not in referenced_paths]
    json_write(output_dir / "contract-inventory.json", {
        "schemaVersion": 1,
        "service": args.service_name,
        "sources": sources,
        "referencedSources": [
            {"path": path.relative_to(service_root).as_posix(), "sha256": sha256(path)}
            for path in sorted(referenced_sources)
        ],
        "ignoredYaml": ignored,
        "operations": operations,
        "generatedCodeBoundary": generated_boundary(service_root),
    })
    json_write(output_dir / "pattern-manifest.json", {
        "schemaVersion": 1,
        "service": args.service_name,
        "patterns": patterns,
        "genericCapabilities": sorted(capabilities),
    })
    json_write(output_dir / "scenario-manifest.json", {
        "schemaVersion": 1,
        "service": args.service_name,
        "scenarios": sorted(scenario_manifest, key=lambda item: (str(item["scenario"]), item["operationKey"])),
    })
    json_write(output_dir / "fixture-manifest.json", {
        "schemaVersion": 1,
        "service": args.service_name,
        "fixtures": fixture_recipes,
    })
    json_write(output_dir / "schema-classification.json", {
        "schemaVersion": 1,
        "service": args.service_name,
        "schemas": classified_schemas,
        "notice": "Candidates require reviewed payload/correlation policy; no examples or defaults are retained.",
    })
    json_write(output_dir / "coverage.json", {
        "schemaVersion": 1,
        "service": args.service_name,
        "operationCount": len(operations),
        "statusCounts": dict(sorted(status_counts.items())),
        "ready": bool(operations) and not uncovered,
        "notCovered": uncovered,
        "coverage": coverage,
    })

    print(f"OPENAPI_DOCUMENTS={len(documents)}")
    print(f"OPERATIONS={len(operations)}")
    print(f"OUTPUT={output_dir}")
    if not documents:
        print("TARGET_SERVICE_ERROR: no OpenAPI YAML/YML was found in the selected service", file=sys.stderr)
        return 3
    if not operations:
        print("TARGET_SERVICE_ERROR: selected OpenAPI documents contain no operations", file=sys.stderr)
        return 3
    if uncovered:
        print(f"TARGET_SERVICE_NOT_READY: {len(uncovered)} operation(s) are NOT_COVERED", file=sys.stderr)
        return 4
    print("TARGET_SERVICE_CONTRACT_READY=true")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ContractError, json.JSONDecodeError, OSError) as error:
        print(f"TARGET_SERVICE_ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
