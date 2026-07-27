#!/usr/bin/env bash
# End-of-rehearsal teardown (E2 — never optional). Drops the stateful namespaces first so the
# CSI driver deletes the PVC-backed EBS volumes (eksctl won't), then deletes the cluster.
set -euo pipefail
cd "$(dirname "$0")"
export AWS_PROFILE="${AWS_PROFILE:-amicro}"
KUBECONFIG_FILE="${KUBECONFIG_FILE:-$HOME/.kube/unoarena-eks}"

if [ -f "$KUBECONFIG_FILE" ]; then
  echo "== releasing PVC-backed EBS volumes (kafka, postgres)"
  KUBECONFIG="$KUBECONFIG_FILE" kubectl delete namespace kafka postgres \
    --ignore-not-found --timeout=180s || true
  for _ in $(seq 1 18); do
    LEFT=$(KUBECONFIG="$KUBECONFIG_FILE" kubectl get pv --no-headers 2>/dev/null | wc -l)
    [ "$LEFT" = "0" ] && break
    sleep 10
  done
fi

echo "== deleting EKS cluster 'unoarena' (~10-15 min)"
time eksctl delete cluster --name unoarena --region us-east-1 --wait --force
rm -f "$KUBECONFIG_FILE"

# Belt and suspenders: CSI-provisioned volumes outlive the cluster when the namespace deletion
# above races the teardown (first rehearsal caught exactly this). Only detached volumes with the
# cluster's dynamic-PVC name tag are touched.
LEFT=$(aws ec2 describe-volumes --region us-east-1 \
  --filters "Name=tag:Name,Values=unoarena-dynamic-pvc-*" "Name=status,Values=available" \
  --query 'Volumes[].VolumeId' --output text)
for v in $LEFT; do
  echo "== deleting leftover PVC volume $v"
  aws ec2 delete-volume --region us-east-1 --volume-id "$v"
done

echo "== done. Now run ./sweep.sh — it must print nothing and exit 0."
