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

# First run generates the plaintexts; later runs read them back, so re-sealing is stable and a
# cluster rebuilt from git still opens the database it was already using.
if [ ! -f "$PLAINTEXT" ]; then
  umask 077
  {
    echo "IDENTITY_JWT_SECRET=$(head -c 32 /dev/urandom | base64 | tr -d '=+/')"
    echo "IDENTITY_DB_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '=+/')"
  } > "$PLAINTEXT"
  echo "generated $PLAINTEXT — keep it; without it every secret has to be re-sealed"
fi
set -a; . "$PLAINTEXT"; set +a

seal() { # seal <namespace> <name> <output> <kubectl-create-secret-args...>
  local ns="$1" name="$2" out="$3"; shift 3
  mkdir -p "$(dirname "$out")"
  kubectl create secret generic "$name" -n "$ns" --dry-run=client -o yaml "$@" \
    | kubeseal --cert "$CERT" --format yaml > "$out"
  echo "sealed $out"
}

seal unoarena-staging identity-secrets "$HERE/staging/identity-secrets.yaml" \
  --from-literal=IDENTITY_JWT_SECRET="$IDENTITY_JWT_SECRET" \
  --from-literal=IDENTITY_DB_PASSWORD="$IDENTITY_DB_PASSWORD"
