#!/usr/bin/env bash
# Prints the URL of the running task. There is no load balancer yet, so the
# public IP changes every time the task is replaced (deploy, restart, crash).
set -euo pipefail

REGION="${AWS_REGION:-ca-central-1}"
NAME="payments-ledger"

TASK="$(aws ecs list-tasks --region "$REGION" --cluster "$NAME" --service-name "$NAME" \
  --desired-status RUNNING --query 'taskArns[0]' --output text)"
if [[ -z "$TASK" || "$TASK" == "None" ]]; then
  echo "No running task found for service $NAME in $REGION." >&2
  exit 1
fi

ENI="$(aws ecs describe-tasks --region "$REGION" --cluster "$NAME" --tasks "$TASK" \
  --query "tasks[0].attachments[0].details[?name=='networkInterfaceId'].value" --output text)"
IP="$(aws ec2 describe-network-interfaces --region "$REGION" --network-interface-ids "$ENI" \
  --query 'NetworkInterfaces[0].Association.PublicIp' --output text)"

echo "App:        http://$IP:8080"
echo "Swagger UI: http://$IP:8080/swagger-ui.html"
echo "Health:     http://$IP:8080/actuator/health"
