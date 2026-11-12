#!/usr/bin/env bash
# Make executable: chmod +x setup-kind.sh
set -euo pipefail

CLUSTER_NAME="it-sm"
# Checked first so a missing value fails before anything is created. The same host must be passed to deploy.sh.
PUBLIC_HOST="${PUBLIC_HOST:?set PUBLIC_HOST to the public hostname you will pass to deploy.sh}"

echo "Creating Kind cluster: $CLUSTER_NAME"
cat <<EOF | kind create cluster --name $CLUSTER_NAME --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  kubeadmConfigPatches:
  - |
    kind: InitConfiguration
    nodeRegistration:
      kubeletExtraArgs:
        node-labels: "ingress-ready=true"
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
- role: worker
- role: worker
EOF

echo "Installing NGINX Ingress Controller..."
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

echo "Waiting for ingress controller..."
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=90s

echo "Building Docker images..."
docker build -t it-sm/backend:latest ../backend
# The browser-facing API origin is compiled into the frontend image: use the host you will deploy with (bare https origin, no /api).
docker build --build-arg NEXT_PUBLIC_API_URL="https://${PUBLIC_HOST}" -t it-sm/frontend:latest ../frontend

echo "Loading images into Kind cluster..."
kind load docker-image it-sm/backend:latest --name $CLUSTER_NAME
kind load docker-image it-sm/frontend:latest --name $CLUSTER_NAME

echo "Kind cluster '$CLUSTER_NAME' is ready!"
echo "Run: ./deploy.sh to deploy the application"
