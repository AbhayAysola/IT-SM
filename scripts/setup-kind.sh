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

# HTTPS is mandatory (the session cookie is Secure), so give the demo a certificate. SELF-SIGNED and for local use only: the browser will
# warn once. The namespace is created here (a Secret needs it) with Helm's ownership metadata so that `helm install` adopts it instead of
# failing because the chart also declares it.
TLS_SECRET_NAME="${TLS_SECRET_NAME:-it-sm-tls}"
echo "Creating a self-signed certificate for ${PUBLIC_HOST} (Secret ${TLS_SECRET_NAME})..."
cat <<YAML | kubectl apply -f -
apiVersion: v1
kind: Namespace
metadata:
  name: it-sm
  labels:
    app.kubernetes.io/managed-by: Helm
  annotations:
    meta.helm.sh/release-name: it-sm
    meta.helm.sh/release-namespace: it-sm
YAML
CERT_DIR="$(mktemp -d)"
trap 'rm -rf "$CERT_DIR"' EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -keyout "$CERT_DIR/tls.key" -out "$CERT_DIR/tls.crt" \
  -subj "/CN=${PUBLIC_HOST}" -addext "subjectAltName=DNS:${PUBLIC_HOST}" 2>/dev/null
kubectl -n it-sm create secret tls "$TLS_SECRET_NAME" --cert="$CERT_DIR/tls.crt" --key="$CERT_DIR/tls.key" --dry-run=client -o yaml | kubectl apply -f -

echo "Building Docker images..."
docker build -t it-sm/backend:latest ../backend
# The browser-facing API origin is compiled into the frontend image: use the host you will deploy with (bare https origin, no /api).
docker build --build-arg NEXT_PUBLIC_API_URL="https://${PUBLIC_HOST}" --build-arg NEXT_PUBLIC_INCIDENTS_API=live -t it-sm/frontend:latest ../frontend

echo "Loading images into Kind cluster..."
kind load docker-image it-sm/backend:latest --name $CLUSTER_NAME
kind load docker-image it-sm/frontend:latest --name $CLUSTER_NAME

echo "Kind cluster '$CLUSTER_NAME' is ready!"
echo "Next: add '127.0.0.1 ${PUBLIC_HOST}' to /etc/hosts, then deploy with the same host:"
echo "  PUBLIC_HOST=${PUBLIC_HOST} TLS_SECRET_NAME=${TLS_SECRET_NAME} TRUSTED_PROXY_CIDRS=10.244.0.0/16 \\"
echo "  JWT_SECRET=\$(openssl rand -base64 48) POSTGRES_PASSWORD=... REDIS_PASSWORD=... RABBITMQ_PASSWORD=... ./deploy.sh"
