#!/usr/bin/env bash
# Offline install bundle (Phase 10, US-INFRA-09). Builds the app images, packages
# the Helm chart, and `docker save`s everything into ONE tarball for air-gapped
# on-prem: copy it across, `docker load`, push to the in-cluster/private registry,
# then `helm install`. See docs/deployment.md.
#
# Usage:
#   scripts/bundle.sh [VERSION] [OUT_DIR]
# Env:
#   CESIUM_ION_TOKEN   optional public Cesium ion token baked into the frontend
set -euo pipefail

VERSION="${1:-0.1.0}"
OUT_DIR="${2:-dist-bundle}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELM="${HELM:-helm}"

BACKEND_IMAGE="orbit-backend:${VERSION}"
FRONTEND_IMAGE="orbit-frontend:${VERSION}"
# Infra images the chart pulls — bundled so the target cluster needs no internet.
INFRA_IMAGES=("postgres:16" "quay.io/keycloak/keycloak:26.0")

mkdir -p "${ROOT}/${OUT_DIR}"

echo "==> Building backend image ${BACKEND_IMAGE}"
docker build -t "${BACKEND_IMAGE}" "${ROOT}/backend"

echo "==> Building frontend image ${FRONTEND_IMAGE}"
docker build -f "${ROOT}/frontend/Dockerfile.prod" \
  --build-arg "VITE_CESIUM_ION_TOKEN=${CESIUM_ION_TOKEN:-}" \
  -t "${FRONTEND_IMAGE}" "${ROOT}/frontend"

echo "==> Pulling infra images"
for img in "${INFRA_IMAGES[@]}"; do docker pull "${img}"; done

echo "==> docker save → images.tar"
docker save "${BACKEND_IMAGE}" "${FRONTEND_IMAGE}" "${INFRA_IMAGES[@]}" \
  -o "${ROOT}/${OUT_DIR}/orbit-images-${VERSION}.tar"

echo "==> helm package"
"${HELM}" package "${ROOT}/deploy/helm/orbit" --version "${VERSION}" --app-version "${VERSION}" \
  -d "${ROOT}/${OUT_DIR}"

echo
echo "Bundle ready in ${OUT_DIR}/:"
ls -1 "${ROOT}/${OUT_DIR}"
echo
echo "On the air-gapped host:"
echo "  docker load -i orbit-images-${VERSION}.tar"
echo "  # docker tag + push each image into your private/in-cluster registry, then:"
echo "  helm install orbit orbit-${VERSION}.tgz -f my-values.yaml"
