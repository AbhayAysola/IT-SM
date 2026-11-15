#!/usr/bin/env bash
# Make executable: chmod +x deploy.sh
set -euo pipefail

RELEASE_NAME="it-sm"
CHART_DIR="$(dirname $0)/../infra/helm"

# HTTPS only (the session cookie is Secure). Both values are yours to provide; nothing here invents a domain or a certificate.
#   PUBLIC_HOST     the public hostname (the repository default `it-sm.local` is only a placeholder)
#   TLS_SECRET_NAME an EXISTING kubernetes.io/tls Secret in the `it-sm` namespace with a certificate valid for PUBLIC_HOST:
#                   kubectl -n it-sm create secret tls <name> --cert=<tls.crt> --key=<tls.key>
#   JWT_SECRET      >= 32 bytes of random secret (openssl rand -base64 48)
#   POSTGRES_PASSWORD, REDIS_PASSWORD, RABBITMQ_PASSWORD
#                   strong passwords you choose. There are no defaults. Keep them: the database keeps the one it was first created with, so
#                   a later deploy must pass the same values (a random value on every run would lock the backend out).
#   TRUSTED_PROXY_CIDRS
#                   the ingress controller's pod CIDR. X-Forwarded-For is trusted from there only. Kind with scripts/setup-kind.sh: 10.244.0.0/16
: "${PUBLIC_HOST:?set PUBLIC_HOST to the public hostname}"
: "${TLS_SECRET_NAME:?set TLS_SECRET_NAME to an existing kubernetes.io/tls Secret in the it-sm namespace}"
: "${JWT_SECRET:?set JWT_SECRET (openssl rand -base64 48)}"
: "${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}"
: "${REDIS_PASSWORD:?set REDIS_PASSWORD}"
: "${RABBITMQ_PASSWORD:?set RABBITMQ_PASSWORD}"
#   GRAFANA_ADMIN_PASSWORD (optional)
#                   when set, also deploys in-cluster Prometheus and Grafana (reach Grafana with kubectl port-forward, see the output below)
: "${TRUSTED_PROXY_CIDRS:?set TRUSTED_PROXY_CIDRS to the ingress controller pod CIDR (Kind: 10.244.0.0/16)}"

MONITORING_ARGS=()
if [ -n "${GRAFANA_ADMIN_PASSWORD:-}" ]; then
  MONITORING_ARGS=(--set monitoring.prometheus.enabled=true --set monitoring.grafana.enabled=true --set "monitoring.grafana.adminPassword=$GRAFANA_ADMIN_PASSWORD")
fi

echo "Deploying $RELEASE_NAME..."
helm upgrade --install $RELEASE_NAME $CHART_DIR \
  --namespace it-sm \
  --create-namespace \
  --set ingress.host="$PUBLIC_HOST" \
  --set ingress.tls.secretName="$TLS_SECRET_NAME" \
  --set backend.jwt.secret="$JWT_SECRET" \
  --set postgres.password="$POSTGRES_PASSWORD" \
  --set redis.password="$REDIS_PASSWORD" \
  --set rabbitmq.password="$RABBITMQ_PASSWORD" \
  --set backend.trustedProxy.cidrs="$TRUSTED_PROXY_CIDRS" \
  "${MONITORING_ARGS[@]}" \
  --wait \
  --timeout 5m

echo "Deployment complete!"
echo "Make sure $PUBLIC_HOST resolves to your ingress (for a local Kind cluster: 127.0.0.1 $PUBLIC_HOST in /etc/hosts)."
echo "Frontend: https://$PUBLIC_HOST"
echo "API: https://$PUBLIC_HOST/api"
echo "The frontend image must have been built with --build-arg NEXT_PUBLIC_API_URL=https://$PUBLIC_HOST (scripts/setup-kind.sh does this)."
echo "Pods:"
kubectl get pods -n it-sm
if [ -n "${GRAFANA_ADMIN_PASSWORD:-}" ]; then
  echo "Grafana: kubectl -n it-sm port-forward svc/it-sm-grafana 3001:3000   (then http://localhost:3001, user admin)"
fi
