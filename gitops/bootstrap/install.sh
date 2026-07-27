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
# The repo is private: Argo needs read access. Pass a token with read_repository (CI uses its job
# token; locally use your PAT). Empty = skip, useful if the repo secret already exists.
GITOPS_REPO_TOKEN="${GITOPS_REPO_TOKEN:-}"
GITOPS_REPO_USER="${GITOPS_REPO_USER:-oauth2}"
REPO_URL="https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp.git"

if command -v kind >/dev/null && [ "${USE_KIND:-true}" = "true" ]; then
  kind get clusters | grep -q "^${CLUSTER_NAME}$" || \
    kind create cluster --name "$CLUSTER_NAME" --config "$(dirname "$0")/kind-cluster.yaml"
fi

# The repo is private: without a token or a pre-existing repo secret every app would sit in
# "authentication required" — fail fast before installing anything (behaviour contract).
if [ -z "$GITOPS_REPO_TOKEN" ] && ! kubectl -n argocd get secret repo-amicro >/dev/null 2>&1; then
  echo "ERROR: repo is private and Argo has no credentials. Pass GITOPS_REPO_TOKEN=<token with read_repository>." >&2
  exit 1
fi

# Argo CD — pinned (R3: `stable` drifts). Needs >=3.x: on Kubernetes 1.33+ the 2.x
# structured-merge-diff schemas break server-side-apply diffing (status.terminatingReplicas),
# which the platform apps rely on for oversized operator CRDs. Applied server-side for the same
# CRD-size reason; --force-conflicts keeps re-runs/upgrades over older installs boring.
# (The v2.12.3 argocd CLI pinned in ci/ is unaffected: that job installs its own Argo.)
kubectl get ns argocd >/dev/null 2>&1 || kubectl create namespace argocd
kubectl apply --server-side --force-conflicts -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v3.4.5/manifests/install.yaml
kubectl -n argocd rollout status deploy/argocd-server --timeout=180s

# Sealed Secrets controller (vendor-neutral secret backend), pinned for the same reason
kubectl apply -f https://github.com/bitnami/sealed-secrets/releases/download/v0.38.4/controller.yaml
kubectl -n kube-system rollout status deploy/sealed-secrets-controller --timeout=120s || true

# Repo read access (same declarative pattern the integration-staging CI job uses)
if [ -n "$GITOPS_REPO_TOKEN" ]; then
  kubectl apply -f - <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: repo-amicro
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: ${REPO_URL}
  username: ${GITOPS_REPO_USER}
  password: ${GITOPS_REPO_TOKEN}
EOF
fi

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
