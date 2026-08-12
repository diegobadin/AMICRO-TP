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

# The passwords we own are generated once and read back afterwards, so re-sealing is stable and a
# cluster rebuilt from git still opens the database it was already using. A phase that adds a
# service appends its password here; an existing one is never regenerated, or every cluster already
# holding the old value would stop authenticating.
umask 077
touch "$PLAINTEXT"
generate() { # generate <VAR> <byte-length>
  grep -q "^$1=" "$PLAINTEXT" && return 0
  echo "$1=$(head -c "$2" /dev/urandom | base64 | tr -d '=+/')" >> "$PLAINTEXT"
  echo "generated $1 in $PLAINTEXT — keep the file; without it every secret has to be re-sealed"
}
generate IDENTITY_JWT_SECRET 32
generate IDENTITY_DB_PASSWORD 24
generate ROOM_GAMEPLAY_DB_PASSWORD 24
generate RANKING_DB_PASSWORD 24
generate ANALYTICS_DB_PASSWORD 24
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

# Its database password and nothing else. The copy of identity's signing key left in P4: the
# gateway validates now, and room-gameplay trusts the headers it passes down (CHANGELOG-design.md
# §8.9, closed).
seal "$HERE/staging/room-gameplay-secrets.yaml" \
  generic room-gameplay-secrets -n unoarena-staging \
  --from-literal=ROOM_GAMEPLAY_DB_PASSWORD="$ROOM_GAMEPLAY_DB_PASSWORD"

# The two P6 consumers. Each owns its own database and holds nothing else: they read Kafka, which
# carries no auth on this platform, and write only their own projections. spectator gets no secret
# at all — its store is Redis and its input is Kafka, neither of which asks for a credential here.
seal "$HERE/staging/ranking-secrets.yaml" \
  generic ranking-secrets -n unoarena-staging \
  --from-literal=RANKING_DB_PASSWORD="$RANKING_DB_PASSWORD"

seal "$HERE/staging/analytics-secrets.yaml" \
  generic analytics-secrets -n unoarena-staging \
  --from-literal=ANALYTICS_DB_PASSWORD="$ANALYTICS_DB_PASSWORD"

# The gateway is the verifier: it validates the token and passes the identity downstream as headers.
# One signer, one verifier — which is as small as a symmetric key's blast radius gets.
seal "$HERE/staging/gateway-secrets.yaml" \
  generic gateway-secrets -n unoarena-staging \
  --from-literal=IDENTITY_JWT_SECRET="$IDENTITY_JWT_SECRET"

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

seal "$HERE/../platform/postgres/room-gameplay-db-role.yaml" \
  generic room-gameplay-db-role -n postgres \
  --type=kubernetes.io/basic-auth \
  --from-literal=username=room_gameplay \
  --from-literal=password="$ROOM_GAMEPLAY_DB_PASSWORD"

seal "$HERE/../platform/postgres/ranking-db-role.yaml" \
  generic ranking-db-role -n postgres \
  --type=kubernetes.io/basic-auth \
  --from-literal=username=ranking \
  --from-literal=password="$RANKING_DB_PASSWORD"

seal "$HERE/../platform/postgres/analytics-db-role.yaml" \
  generic analytics-db-role -n postgres \
  --type=kubernetes.io/basic-auth \
  --from-literal=username=analytics \
  --from-literal=password="$ANALYTICS_DB_PASSWORD"
