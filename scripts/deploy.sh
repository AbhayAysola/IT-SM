#!/usr/bin/env bash
# Make executable: chmod +x deploy.sh
set -euo pipefail

RELEASE_NAME="it-sm"
CHART_DIR="$(dirname $0)/../infra/helm"

echo "Deploying $RELEASE_NAME..."
helm upgrade --install $RELEASE_NAME $CHART_DIR \
  --namespace it-sm \
  --create-namespace \
  --wait \
  --timeout 5m

echo "Deployment complete!"
echo "Add to /etc/hosts: 127.0.0.1 it-sm.local"
echo "Frontend: http://it-sm.local"
echo "API: http://it-sm.local/api"
echo "Pods:"
kubectl get pods -n it-sm
