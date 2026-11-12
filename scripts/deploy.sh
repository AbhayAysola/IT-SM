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
: "${PUBLIC_HOST:?set PUBLIC_HOST to the public hostname}"
: "${TLS_SECRET_NAME:?set TLS_SECRET_NAME to an existing kubernetes.io/tls Secret in the it-sm namespace}"
: "${JWT_SECRET:?set JWT_SECRET (openssl rand -base64 48)}"

echo "Deploying $RELEASE_NAME..."
helm upgrade --install $RELEASE_NAME $CHART_DIR \
  --namespace it-sm \
  --create-namespace \
  --set ingress.host="$PUBLIC_HOST" \
  --set ingress.tls.secretName="$TLS_SECRET_NAME" \
  --set backend.jwt.secret="$JWT_SECRET" \
  --wait \
  --timeout 5m

echo "Deployment complete!"
echo "Make sure $PUBLIC_HOST resolves to your ingress (for a local Kind cluster: 127.0.0.1 $PUBLIC_HOST in /etc/hosts)."
echo "Frontend: https://$PUBLIC_HOST"
echo "API: https://$PUBLIC_HOST/api"
echo "The frontend image must have been built with --build-arg NEXT_PUBLIC_API_URL=https://$PUBLIC_HOST (scripts/setup-kind.sh does this)."
echo "Pods:"
kubectl get pods -n it-sm
