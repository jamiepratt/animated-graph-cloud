# Alpha Compose agent guide

- Production infrastructure is managed with Terraform in `infra/prod`; do not
  treat an application image deployment as a complete production deployment.
- Every push to `main` automatically runs `.github/workflows/deploy-production.yml`.
  Before pushing deployment or Terraform changes, verify that the workflow
  applies all required infrastructure and runtime configuration.
- The production workflow must plan and apply Terraform before promoting the
  application. Keep its immutable renderer image and Cloud Run origin inputs in
  sync with the live release so infrastructure changes do not roll back code.
- Never delete an existing database or other production resource to resolve an
  `AlreadyExists` error. Stop and warn the user that a Terraform state import is
  required. Firestore import is allowed only through the workflow's explicit
  manual input or a reviewed operator command.
- Warn the user before a release if it needs a data/schema migration, resource
  import, secret version, OAuth/Firebase/DNS action, or other manual checkpoint.
  Put repeatable configuration in the normal workflow. If it cannot run safely
  there, provide a guarded `workflow_dispatch` recovery path and document the
  remaining owner action.
