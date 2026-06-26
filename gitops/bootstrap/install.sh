#!/usr/bin/env bash
# Stand up the local staging cluster (no cloud): a kind cluster + Argo CD + Sealed Secrets
# controller, then register the UnoArena AppProject and the app-of-apps. Idempotent. Run once.
# Per consigna §4 the cluster is assumed to exist; this is the kind/k3d local equivalent.
#
# To use an already-existing cluster instead, set USE_KIND=false and point kubectl at it; the rest
# is identical.
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-unoarena-staging}"

if command -v kind >/dev/null && [ "${USE_KIND:-true}" = "true" ]; then
  kind get clusters | grep -q "^${CLUSTER_NAME}$" || \
    kind create cluster --name "$CLUSTER_NAME" --config "$(dirname "$0")/kind-cluster.yaml"
fi

# Argo CD
kubectl get ns argocd >/dev/null 2>&1 || kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl -n argocd rollout status deploy/argocd-server --timeout=180s

# Sealed Secrets controller (vendor-neutral secret backend)
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/latest/download/controller.yaml
kubectl -n kube-system rollout status deploy/sealed-secrets-controller --timeout=120s || true

# Register the project and the app-of-apps (edit REPLACE_REPO_URL first)
kubectl apply -f "$(dirname "$0")/../projects/unoarena.yaml"
kubectl apply -f "$(dirname "$0")/../root-app.yaml"

echo "Bootstrap complete. Argo CD is reconciling gitops/applications."
echo "Get the initial admin password:"
echo "  kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
