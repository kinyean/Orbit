# Frontend dev container. For Phase 1 this runs the Vite dev server.
# Source is bind-mounted in docker-compose for hot reload; the container's
# baked-in source is just a fallback for standalone use.

FROM node:24-alpine
WORKDIR /app

# Install deps in a cacheable layer.
COPY package*.json ./
RUN npm ci

# Bake in the source as a fallback (the compose file overlays a bind mount).
COPY . .

EXPOSE 5173
# --host 0.0.0.0 so the container's loopback isn't the only thing listening;
# port-forwarded from the host via compose.
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
