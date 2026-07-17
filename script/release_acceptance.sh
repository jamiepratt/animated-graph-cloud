#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

acceptance_image="${ALPHA_COMPOSE_ACCEPTANCE_IMAGE:-alpha-compose:release-acceptance}"

for command in clojure clj-kondo terraform node npx docker trivy rg; do
  command -v "$command" >/dev/null || {
    echo "Missing required command: $command" >&2
    exit 1
  }
done

echo "[1/8] Clojure lint and complete automated test suite"
clj-kondo --lint src test build.clj
clojure -M:test

echo "[2/8] Production artifact build"
clojure -T:build uber

echo "[3/8] Terraform formatting"
terraform fmt -check -recursive

echo "[4/8] Development Terraform validation (remote backend disabled)"
terraform -chdir=infra/dev init -backend=false -input=false
terraform -chdir=infra/dev validate

echo "[5/8] Production Terraform validation (remote backend disabled)"
terraform -chdir=infra/prod init -backend=false -input=false
terraform -chdir=infra/prod validate

echo "[6/8] OpenAPI lint"
npx --yes @redocly/cli@2.39.0 lint docs/openapi.yaml

echo "[7/8] Repository secret-pattern review"
if rg -n --hidden \
  --glob 'infra/prod/**' \
  --glob '.github/workflows/deploy-production.yml' \
  --glob '!infra/prod/.terraform.lock.hcl' \
  '(-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|private_key_id|private_key_data|service_account_key)' .; then
  echo "Potential credential material found in production configuration" >&2
  exit 1
fi

echo "[8/8] Container entry points and HIGH/CRITICAL vulnerability scan"
docker build --tag "$acceptance_image" .
test/container_smoke.sh "$acceptance_image"
trivy image --exit-code 1 --ignore-unfixed \
  --severity HIGH,CRITICAL --scanners vuln "$acceptance_image"

echo "Repository-side automated acceptance passed."
echo "No production deployment, Firebase activation, OAuth publication, load test, or manual acceptance was performed."
