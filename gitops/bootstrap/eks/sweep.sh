#!/usr/bin/env bash
# Lists every EKS/EC2/NAT/ELB/EBS resource (plus eksctl CloudFormation stacks) still alive in
# us-east-1. Empty output + exit 0 == the Learner Lab bills nothing (AC-P1.4). Anything printed
# is a leftover to hunt down before ending the session.
set -euo pipefail
export AWS_PROFILE="${AWS_PROFILE:-amicro}"
REGION=us-east-1

aws sts get-caller-identity >/dev/null 2>&1 || {
  echo "ERROR: AWS credentials rejected — cannot sweep. Refresh the lab credentials first." >&2
  exit 2
}

found=0
check() {
  local label="$1"; shift
  local out
  if ! out=$("$@" --region "$REGION" --output text 2>&1); then
    echo "SWEEP-QUERY-FAILED $label: $out"; found=1; return 0
  fi
  if [ -n "$out" ]; then
    echo "LEFTOVER $label: $out"; found=1
  fi
  return 0
}

check eks-cluster aws eks list-clusters --query 'clusters'
check ec2-instance aws ec2 describe-instances \
  --query 'Reservations[].Instances[?State.Name!=`terminated`][].InstanceId'
check nat-gateway aws ec2 describe-nat-gateways \
  --query 'NatGateways[?State!=`deleted`].NatGatewayId'
check elbv2 aws elbv2 describe-load-balancers --query 'LoadBalancers[].LoadBalancerArn'
check elb-classic aws elb describe-load-balancers \
  --query 'LoadBalancerDescriptions[].LoadBalancerName'
check ebs-volume aws ec2 describe-volumes --query 'Volumes[].VolumeId'
check cloudformation aws cloudformation list-stacks \
  --stack-status-filter CREATE_COMPLETE CREATE_IN_PROGRESS CREATE_FAILED DELETE_IN_PROGRESS \
    DELETE_FAILED ROLLBACK_COMPLETE UPDATE_COMPLETE \
  --query 'StackSummaries[?starts_with(StackName, `eksctl-`)].StackName'

exit $found
