#!/usr/bin/env bash
# Deploys the app to AWS (minimal setup, see docs/DECISIONS.md).
#
#   1. Creates the ECR repository (the ECS service needs an image to pull).
#   2. Builds the Docker image, tags it with the git SHA and pushes it.
#   3. Applies the rest of the Terraform with that image tag.
#   4. Waits for the service to be stable and prints the app URL.
#
# Needs: terraform, aws (configured with credentials), docker, git.
# Needs: infra/terraform/terraform.tfvars (copy terraform.tfvars.example).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF_DIR="$ROOT/infra/terraform"
REGION="${AWS_REGION:-ca-central-1}"
NAME="payments-ledger"

for tool in terraform aws docker git; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
[[ -f "$TF_DIR/terraform.tfvars" ]] || {
  echo "Create $TF_DIR/terraform.tfvars first (see terraform.tfvars.example)." >&2
  exit 1
}

TAG="$(git -C "$ROOT" rev-parse --short HEAD)"
if ! git -C "$ROOT" diff --quiet HEAD; then
  TAG="$TAG-dirty-$(date +%s)"
fi

cd "$TF_DIR"
terraform init -input=false

echo "==> Creating the ECR repository"
terraform apply -input=false -var "region=$REGION" \
  -target=aws_ecr_repository.app -target=aws_ecr_lifecycle_policy.app

REPO="$(aws ecr describe-repositories --region "$REGION" --repository-names "$NAME" \
  --query 'repositories[0].repositoryUri' --output text)"

echo "==> Building and pushing $REPO:$TAG"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "${REPO%%/*}"
docker build --platform linux/amd64 -t "$REPO:$TAG" "$ROOT"
docker push "$REPO:$TAG"

echo "==> Applying the rest of the infrastructure (RDS takes ~5-10 minutes the first time)"
terraform apply -input=false -var "region=$REGION" -var "image_tag=$TAG"

echo "==> Waiting for the service to become stable"
aws ecs wait services-stable --region "$REGION" --cluster "$NAME" --services "$NAME"

AWS_REGION="$REGION" "$ROOT/scripts/app-url.sh"
