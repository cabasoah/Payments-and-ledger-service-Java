#!/usr/bin/env bash
# Destroys everything deploy.sh created, including the database and its data.
# Terraform asks for confirmation; pass -y to skip it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGION="${AWS_REGION:-ca-central-1}"

ARGS=()
if [[ "${1:-}" == "-y" ]]; then
  ARGS+=(-auto-approve)
fi

cd "$ROOT/infra/terraform"
terraform init -input=false
terraform destroy -input=false -var "region=$REGION" ${ARGS[@]+"${ARGS[@]}"}

echo
echo "Done. Check the AWS Billing console in a day to confirm nothing is still charging."
