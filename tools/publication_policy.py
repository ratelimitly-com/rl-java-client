"""Fail-closed decisions for the manual Maven workflow; no network or credentials."""
import json
import os
import re
import sys

REQUIRED_CHECKS = (
    "hygiene", "build and test (Linux, JDK 21)", "build and test (Linux, JDK 25)",
    "build and test (macOS, JDK 21)", "build and test (Windows, JDK 21)",
    "Analyze (java-kotlin)", "Analyze (actions)", "Analyze (python)",
)


def validate_request(*, event, ref, sha, expected_sha, version, expected_version,
                     action, attempt):
    if event not in ("push", "pull_request", "workflow_dispatch"):
        raise ValueError("unsupported workflow event")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("a full checkout SHA is required")
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+_-]*", version):
        raise ValueError("invalid project version")
    if action == "dry-run":
        return action
    if action not in ("publish", "finalize"):
        raise ValueError("unsupported publication action")
    if event != "workflow_dispatch" or ref != "refs/heads/main":
        raise ValueError("publication operations require manual dispatch from main")
    if sha != expected_sha or version != expected_version:
        raise ValueError("checkout SHA/version do not match the operator's expected values")
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("publication requires a numeric non-SNAPSHOT version")
    if action == "publish" and attempt != "1":
        raise ValueError("never rerun a possibly completed upload; inspect and finalize instead")
    return action


def validate_checks(rows, sha):
    for name in REQUIRED_CHECKS:
        matches = [r for r in rows if r.get("name") == name and
                   r.get("app", {}).get("id") == 15368 and r.get("head_sha") == sha]
        latest = max(matches, key=lambda row: row["id"], default={})
        if latest.get("status") != "completed" or latest.get("conclusion") != "success":
            raise ValueError("required exact-commit check is not successful: " + name)


def validate_analyses(rows, sha):
    for language in ("actions", "java-kotlin", "python"):
        matches = [r for r in rows if r.get("commit_sha") == sha and
                   r.get("tool", {}).get("name") == "CodeQL" and
                   r.get("category", "").endswith("/language:" + language)]
        latest = max(matches, key=lambda row: row["id"], default={})
        if latest.get("error") != "" or not isinstance(latest.get("results_count"), int):
            raise ValueError("missing or failed exact-commit CodeQL analysis: " + language)


def validate_alerts(rows):
    # results_count also includes findings already dismissed after review. Gate
    # unresolved high/critical security findings and error-level code findings.
    for row in rows:
        rule = row.get("rule", {})
        if row.get("state") == "open" and (rule.get("security_severity_level") in ("high", "critical")
                                           or rule.get("severity") == "error"):
            raise ValueError("unresolved blocking CodeQL alert: " + str(row.get("number")))


def main():
    command = sys.argv[1]
    if command == "request":
        action = validate_request(
            event=os.environ["GITHUB_EVENT_NAME"], ref=os.environ["GITHUB_REF"],
            sha=os.environ["COMMIT"], expected_sha=os.environ.get("EXPECTED_COMMIT", ""),
            version=os.environ["VERSION"], expected_version=os.environ.get("EXPECTED_VERSION", ""),
            action=os.environ.get("ACTION", "") or "dry-run",
            attempt=os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
        print("action=" + action)
        print("build=true")
        print("commit=" + os.environ["COMMIT"])
        print("version=" + os.environ["VERSION"])
        print("tag=v" + os.environ["VERSION"])
    elif command == "checks":
        pages = json.load(sys.stdin)
        validate_checks([row for page in pages for row in page["check_runs"]], sys.argv[2])
    elif command == "analyses":
        validate_analyses([row for page in json.load(sys.stdin) for row in page], sys.argv[2])
    elif command == "alerts":
        validate_alerts([row for page in json.load(sys.stdin) for row in page])
    else:
        raise ValueError("unknown policy command")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, IndexError) as error:
        sys.exit("Publication refused: " + str(error))
