#!/usr/bin/env bash
# Stand up the local staging cluster (no cloud): a kind cluster + Argo CD + Sealed Secrets
# controller, then register the UnoArena AppProjects and both app-of-apps (services + platform).
# Idempotent. Run once.
# Per consigna §4 the cluster is assumed to exist; this is the kind/k3d local equivalent.
#
# To use an already-existing cluster instead, set USE_KIND=false and point kubectl at it; the rest
# is identical.
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-unoarena-staging}"
# Branch the platform apps track (rehearsal clusters set this to the feature branch, see plan R4).
TARGET_REVISION="${TARGET_REVISION:-main}"

if command -v kind >/dev/null && [ "${USE_KIND:-true}" = "true" ]; then
  kind get clusters | grep -q "^${CLUSTER_NAME}$" || \
    kind create cluster --name "$CLUSTER_NAME" --config "$(dirname "$0")/kind-cluster.yaml"
fi

# Argo CD — pinned to the same version as the CLI in ci/templates/deploy-gitops.yml (R3:
# `stable` drifted until its CRDs no longer fit a client-side apply).
kubectl get ns argocd >/dev/null 2>&1 || kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.12.3/manifests/install.yaml
kubectl -n argocd rollout status deploy/argocd-server --timeout=180s

# Sealed Secrets controller (vendor-neutral secret backend), pinned for the same reason
kubectl apply -f https://github.com/bitnami/sealed-secrets/releases/download/v0.38.4/controller.yaml
kubectl -n kube-system rollout status deploy/sealed-secrets-controller --timeout=120s || true

# Register the projects and both app-of-apps (repoURL already configured to the course repo).
# The platform root gets its git revision rewritten so a rehearsal cluster can track a branch;
# the service root always tracks main (untouched, AC-P1.5).
kubectl apply -f "$(dirname "$0")/../projects/unoarena.yaml"
kubectl apply -f "$(dirname "$0")/../root-app.yaml"
kubectl apply -f "$(dirname "$0")/../projects/unoarena-platform.yaml"
sed -e "s|targetRevision: main|targetRevision: ${TARGET_REVISION}|" \
    -e "s|value: main|value: ${TARGET_REVISION}|" \
    "$(dirname "$0")/../platform-root.yaml" | kubectl apply -f -

echo "Bootstrap complete. Argo CD is reconciling gitops/applications + gitops/platform (${TARGET_REVISION})."
echo "Get the initial admin password:"
echo "  kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
