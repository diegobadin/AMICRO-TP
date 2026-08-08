#!/usr/bin/env bash
# Regenerate every committed SealedSecret from the operator-held plaintexts.
#
# Sealing happens OFFLINE against the certificate of the backed-up controller key, so no cluster
# has to be running and a re-seal can never silently bind the blobs to whichever cluster happens
# to be up. The one-time key backup and the recovery procedure are in README.md.
set -euo pipefail

CERT="${SEALING_CERT_FILE:-$HOME/.amicro_sealing_cert.pem}"
PLAINTEXT="${SECRETS_ENV_FILE:-$HOME/.amicro_secrets.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ ! -f "$CERT" ]; then
  echo "ERROR: no sealing certificate at $CERT — see gitops/secrets/README.md" >&2
  exit 1
fi

# First run generates the passwords we own; later runs read them back, so re-sealing is stable and
# a cluster rebuilt from git still opens the database it was already using.
if [ ! -f "$PLAINTEXT" ]; then
  umask 077
  {
    echo "IDENTITY_JWT_SECRET=$(head -c 32 /dev/urandom | base64 | tr -d '=+/')"
    echo "IDENTITY_DB_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '=+/')"
  } > "$PLAINTEXT"
  echo "generated $PLAINTEXT — keep it; without it every secret has to be re-sealed"
fi
set -a; . "$PLAINTEXT"; set +a

# The registry credential is issued by GitLab, not by us, so it cannot be regenerated here.
if [ -z "${REGISTRY_PULL_TOKEN:-}" ]; then
  echo "ERROR: REGISTRY_PULL_USER/REGISTRY_PULL_TOKEN missing from $PLAINTEXT." >&2
  echo "       Create a project deploy token with the read_registry scope and add it there." >&2
  exit 1
fi

seal() { # seal <output-file> <kubectl-create-secret-args...>
  local out="$1"; shift
  mkdir -p "$(dirname "$out")"
  kubectl create secret "$@" --dry-run=client -o yaml | kubeseal --cert "$CERT" --format yaml > "$out"
  echo "sealed $out"
}

seal "$HERE/staging/identity-secrets.yaml" \
  generic identity-secrets -n unoarena-staging \
  --from-literal=IDENTITY_JWT_SECRET="$IDENTITY_JWT_SECRET" \
  --from-literal=IDENTITY_DB_PASSWORD="$IDENTITY_DB_PASSWORD"

# Read-only registry credential (deploy token, read_registry scope): without it every service sits
# in ImagePullBackOff on a cluster that was just created, because the project registry is private.
seal "$HERE/staging/registry-pull.yaml" \
  docker-registry gitlab-registry -n unoarena-staging \
  --docker-server=registry.gitlab.com \
  --docker-username="$REGISTRY_PULL_USER" \
  --docker-password="$REGISTRY_PULL_TOKEN"

# CNPG reads the role password from the postgres namespace, and SealedSecrets are namespace-scoped,
# so the one plaintext is sealed twice. This copy lives next to the Cluster CR that consumes it.
seal "$HERE/../platform/postgres/identity-db-role.yaml" \
  generic identity-db-role -n postgres \
  --type=kubernetes.io/basic-auth \
  --from-literal=username=identity \
  --from-literal=password="$IDENTITY_DB_PASSWORD"
