#!/usr/bin/env bash
# Open the demo's two NodePorts on the EKS node security group: 30080 (gateway) and 30081 (Grafana).
#
# Nothing did this before P9. The P1 rehearsal predates P4's gateway, so the NodePort path had never
# been exercised on AWS at all — on kind both ports are published by the node at cluster creation
# and the question never comes up. Without this the demo is two `kubectl port-forward` processes
# kept alive in front of the cátedra instead of two URLs.
#
# Scoped to one source address (E1/D2). The lab account is shared-fate and these ports would
# otherwise be open to the internet on a public subnet. The exam will very likely be presented from
# a different network than the rehearsal, which is why this is a script you re-run rather than a
# step buried inside create.sh: `./authorize-nodeports.sh` from the presenting machine, and the old
# rule can be dropped with --revoke.
#
#   ./authorize-nodeports.sh                 # authorize this machine's public IP
#   SOURCE_CIDR=203.0.113.7/32 ./authorize-nodeports.sh
#   ./authorize-nodeports.sh --revoke        # remove the rules for that CIDR
set -euo pipefail
cd "$(dirname "$0")"
export AWS_PROFILE="${AWS_PROFILE:-amicro}"
KUBECONFIG_FILE="${KUBECONFIG_FILE:-$HOME/.kube/unoarena-eks}"
CLUSTER="${CLUSTER:-unoarena}"
REGION="${REGION:-us-east-1}"
PORTS="${PORTS:-30080 30081}"

ACTION=authorize
[ "${1:-}" = "--revoke" ] && ACTION=revoke

ACCOUNT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) || {
  echo "ERROR: AWS credentials rejected — Learner Lab session expired or profile '$AWS_PROFILE' unset." >&2
  exit 1
}
[ "$ACCOUNT" = "811591236522" ] || {
  echo "ERROR: wrong AWS account '$ACCOUNT' (expected Learner Lab 811591236522)." >&2
  exit 1
}

# The cluster security group is the one EKS attaches to managed-nodegroup instances, so it is the
# one a packet to a NodePort actually meets. Asking EKS for it beats guessing at eksctl's SG names.
SG=$(aws eks describe-cluster --name "$CLUSTER" --region "$REGION" \
  --query 'cluster.resourcesVpcConfig.clusterSecurityGroupId' --output text)
[ -n "$SG" ] && [ "$SG" != "None" ] || { echo "ERROR: no cluster security group for '$CLUSTER'." >&2; exit 1; }

CIDR="${SOURCE_CIDR:-$(curl -fsS https://checkip.amazonaws.com)/32}"
echo "== $ACTION ${PORTS// /, } on $SG for $CIDR"

for port in $PORTS; do
  if [ "$ACTION" = authorize ]; then
    # A rule that is already there is the goal state, not an error — this has to survive a second run.
    out=$(aws ec2 authorize-security-group-ingress --group-id "$SG" --region "$REGION" \
      --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=$CIDR,Description=UnoArena NodePort $port}]" \
      2>&1) && echo "   $port opened" || {
        echo "$out" | grep -q 'InvalidPermission.Duplicate' || { echo "$out" >&2; exit 1; }
        echo "   $port already open"
      }
  else
    out=$(aws ec2 revoke-security-group-ingress --group-id "$SG" --region "$REGION" \
      --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=$CIDR}]" \
      2>&1) && echo "   $port closed" || {
        echo "$out" | grep -q 'InvalidPermission.NotFound' || { echo "$out" >&2; exit 1; }
        echo "   $port was not open"
      }
  fi
done

[ "$ACTION" = authorize ] || exit 0

IP=$(KUBECONFIG="$KUBECONFIG_FILE" kubectl get nodes \
  -o jsonpath='{.items[0].status.addresses[?(@.type=="ExternalIP")].address}' 2>/dev/null || true)
if [ -n "$IP" ]; then
  echo "== the demo's two URLs:"
  echo "   gateway  http://$IP:30080"
  echo "   Grafana  http://$IP:30081   (admin / GRAFANA_ADMIN_PASSWORD in ~/.amicro_secrets.env)"
else
  echo "== no node ExternalIP yet (cluster still coming up?). Re-run this script once nodes are Ready."
fi
