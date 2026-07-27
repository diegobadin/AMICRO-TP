#!/usr/bin/env bash
# Create the exam-shaped EKS cluster in the Learner Lab (D6). Golden rule (E2): every rehearsal
# ends with ./destroy.sh + an empty ./sweep.sh — EKS/EC2/EBS keep billing while the lab is OFF.
set -euo pipefail
cd "$(dirname "$0")"
export AWS_PROFILE="${AWS_PROFILE:-amicro}"
KUBECONFIG_FILE="${KUBECONFIG_FILE:-$HOME/.kube/unoarena-eks}"

# Fail fast on expired Learner Lab credentials (~4h) instead of half-creating resources.
ACCOUNT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) || {
  echo "ERROR: AWS credentials rejected — Learner Lab session expired or profile '$AWS_PROFILE' unset." >&2
  echo "       Start the lab, refresh the CLI credentials, then retry." >&2
  exit 1
}
[ "$ACCOUNT" = "811591236522" ] || {
  echo "ERROR: wrong AWS account '$ACCOUNT' (expected Learner Lab 811591236522)." >&2
  exit 1
}

echo "== creating EKS cluster 'unoarena' in us-east-1 (~15-20 min)"
time eksctl create cluster -f cluster.yaml --kubeconfig "$KUBECONFIG_FILE"

# EKS has no default StorageClass — without one, the Kafka/Postgres PVCs hang Pending.
KUBECONFIG="$KUBECONFIG_FILE" kubectl apply -f storageclass.yaml

echo "== cluster up. Install the platform with:"
echo "   export KUBECONFIG=$KUBECONFIG_FILE"
echo "   GITOPS_REPO_TOKEN=<token> USE_KIND=false $(cd .. && pwd)/install.sh"
echo "== when done, ALWAYS: ./destroy.sh && ./sweep.sh"
