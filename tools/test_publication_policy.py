"""Credential-free publication policy regression tests (standard library only)."""
import importlib.util
import pathlib
import unittest


SPEC = importlib.util.spec_from_file_location(
    "publication_policy", pathlib.Path(__file__).with_name("publication_policy.py"))
policy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(policy)
SHA = "a" * 40
ID = "12345678-1234-1234-1234-123456789abc"


class PublicationPolicyTest(unittest.TestCase):
    def test_workflow_keeps_production_secrets_out_of_automatic_builds(self):
        root = pathlib.Path(__file__).resolve().parent.parent
        self.assertFalse((root / ".github/workflows/release.yml").exists())
        workflow = (root / ".github/workflows/publish-mvn.yml").read_text()
        self.assertIn("environment: maven-central", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertNotIn("github.event_name == 'push' &&", workflow)
        central = workflow.split("\n  central:\n", 1)[1].split("\n  attest:\n", 1)[0]
        self.assertNotIn("cache: maven", central)
        self.assertIn("MAVEN_CENTRAL_APPROVED", central)
        self.assertIn("-Dcentral.skipPublishing=false", central)
        self.assertIn("publication_policy.py deployment", central)
        self.assertIn("needs.metadata.outputs.action == 'stage'", central)

    def request(self, **changes):
        values = dict(event="workflow_dispatch", ref="refs/heads/main", sha=SHA,
                      expected_sha=SHA, version="3.0.0", expected_version="3.0.0",
                      action="stage", attempt="1", portal_checked="true", deployment_id="")
        values.update(changes)
        return policy.validate_request(**values)

    def test_only_manual_main_can_stage(self):
        self.assertEqual(self.request(), "stage")
        for changes in ({"event": "push"}, {"event": "pull_request"},
                        {"ref": "refs/heads/feature"}, {"ref": "refs/tags/v3.0.0"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.request(**changes)

    def test_exact_release_identity_and_explicit_fresh_upload_are_required(self):
        for changes in ({"sha": "short"}, {"expected_sha": "b" * 40},
                        {"version": "3.0.0-SNAPSHOT"}, {"expected_version": "3.0.1"},
                        {"version": "3.0.0;echo bad"}, {"portal_checked": "false"},
                        {"attempt": "2"}, {"deployment_id": ID}, {"action": "publish"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.request(**changes)

    def test_push_and_pr_dry_runs_allow_snapshots_but_do_not_stage(self):
        for event in ("push", "pull_request", "workflow_dispatch"):
            self.assertEqual(self.request(event=event, action="dry-run",
                                          version="3.0.0-SNAPSHOT"), "dry-run")

    def test_finalize_requires_id_but_allows_reruns(self):
        self.assertEqual(self.request(action="finalize", deployment_id=ID, attempt="2",
                                      portal_checked="false"), "finalize")
        for value in ("", "../../other", "bad?token=oops"):
            with self.assertRaises(ValueError):
                self.request(action="finalize", deployment_id=value)

    def checks(self):
        return [{"id": n, "name": name, "head_sha": SHA, "app": {"id": 15368},
                 "status": "completed", "conclusion": "success"}
                for n, name in enumerate(policy.REQUIRED_CHECKS, 1)]

    def test_all_exact_commit_checks_required(self):
        policy.validate_checks(self.checks(), SHA)
        with self.assertRaises(ValueError):
            policy.validate_checks(self.checks()[:-1], SHA)
        for field, value in (("conclusion", "skipped"), ("conclusion", "failure"),
                             ("status", "in_progress"), ("head_sha", "b" * 40),
                             ("app", {"id": 1})):
            rows = self.checks()
            rows[0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                policy.validate_checks(rows, SHA)

    def test_latest_rerun_cannot_hide_behind_old_success(self):
        rows = self.checks()
        rows.append(dict(rows[0], id=1000, conclusion="failure"))
        with self.assertRaises(ValueError):
            policy.validate_checks(rows, SHA)

    def test_analysis_must_be_exact_and_successful(self):
        rows = [{"id": n, "commit_sha": SHA, "category": "/language:" + language,
                 "tool": {"name": "CodeQL"}, "error": "", "results_count": 0}
                for n, language in enumerate(("actions", "java-kotlin"), 1)]
        policy.validate_analyses(rows, SHA)
        for changes in ({"error": "failed"}, {"results_count": None},
                        {"commit_sha": "b" * 40}, {"tool": {"name": "other"}}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                policy.validate_analyses([dict(rows[0], **changes), rows[1]], SHA)
        policy.validate_analyses([dict(rows[0], results_count=1), rows[1]], SHA)

    def test_open_blocking_alerts_fail_but_reviewed_dismissals_do_not(self):
        for rule in ({"security_severity_level": "high"}, {"security_severity_level": "critical"},
                     {"severity": "error"}):
            with self.assertRaises(ValueError):
                policy.validate_alerts([{"number": 1, "state": "open", "rule": rule}])
            policy.validate_alerts([{"number": 1, "state": "dismissed", "rule": rule}])
        policy.validate_alerts([{"state": "open", "rule": {"severity": "warning", "security_severity_level": "low"}}])

    def test_only_expected_published_deployment_can_finalize(self):
        data = {"deploymentId": ID, "deploymentState": "PUBLISHED",
                "purls": ["pkg:maven/com.ratelimitly/ratelimitly-java-client@3.0.0"]}
        policy.validate_deployment(data, ID, "3.0.0")
        for state in ("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "FAILED", "UNKNOWN"):
            with self.subTest(state=state), self.assertRaises(ValueError):
                policy.validate_deployment(dict(data, deploymentState=state), ID, "3.0.0")
        for changes in ({"deploymentId": "wrong"}, {"purls": []},
                        {"purls": data["purls"] + ["pkg:maven/com.ratelimitly/server@3.0.0"]}):
            with self.assertRaises(ValueError):
                policy.validate_deployment(dict(data, **changes), ID, "3.0.0")


if __name__ == "__main__":
    unittest.main()
