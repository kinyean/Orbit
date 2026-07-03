#!/bin/sh
# Runtime config injection (Phase 10). Substitutes the ORBIT_* env vars into
# /env.js at container start, so ONE built image serves any deployment. Runs via
# nginx:alpine's /docker-entrypoint.d/ hook (before nginx starts).
set -e

: "${ORBIT_AUTH_MODE:=stub}"
: "${ORBIT_OIDC_ISSUER:=}"
: "${ORBIT_OIDC_CLIENT_ID:=orbit-frontend}"
export ORBIT_AUTH_MODE ORBIT_OIDC_ISSUER ORBIT_OIDC_CLIENT_ID

envsubst < /etc/orbit/env.template.js > /usr/share/nginx/html/env.js
echo "orbit: wrote /env.js (AUTH_MODE=${ORBIT_AUTH_MODE})"
