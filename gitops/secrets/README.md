# Sealed secrets

Secrets are committed here as `SealedSecret`s: encrypted blobs only the in-cluster controller can
open, so no plaintext ever enters the repo (tech-stack §6). The catch is that the controller
generates its own key on first start — a blob sealed against one cluster is undecryptable on the
next one, which would break the exam's opening move ("empty cluster → everything Healthy").

So the key travels with the operator, not with the cluster: `install.sh` restores it **before**
the controller is applied, and every cluster from then on opens the same committed blobs.

## One-time setup (already done)

```bash
# against any cluster running the controller
kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > ~/.amicro_sealing_key
kubeseal --fetch-cert --controller-namespace kube-system > ~/.amicro_sealing_cert.pem
```

Both files are `chmod 600`, live outside the repo, and are git-ignored. **Back them up.** The
plaintext values themselves sit in `~/.amicro_secrets.env`, generated on the first `seal.sh` run.

## Changing or adding a secret

```bash
gitops/secrets/seal.sh      # re-seals everything from ~/.amicro_secrets.env, offline
git add gitops/secrets gitops/platform/postgres && git commit
```

Sealing is offline (`kubeseal --cert`), so it needs no cluster and cannot accidentally bind the
blobs to whatever cluster happens to be up. Argo applies them through the `secrets-<env>` app at
sync wave -1, ahead of the services that consume them.

## If the key is lost

Nothing is unrecoverable, but everything must be re-issued:

1. Let a cluster's controller generate a fresh key, then back it up as above.
2. Re-run `seal.sh` (the plaintexts in `~/.amicro_secrets.env` are unchanged) and commit.
3. If `~/.amicro_secrets.env` is gone too, delete it first — `seal.sh` generates new values, and
   the `identity` role's password in Postgres has to be rotated to match.
