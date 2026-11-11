FROM node:20-alpine AS deps
WORKDIR /app
# Reproducible install: exactly the versions pinned in package-lock.json (npm ci fails if it is out of sync).
COPY package.json package-lock.json ./
RUN npm ci

FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# next/font downloads Inter at build time. Docker bridges often have no IPv6 route, and Node tries the AAAA
# address first and times out (ETIMEDOUT), so prefer IPv4 for DNS results during the build.
ENV NODE_OPTIONS="--dns-result-order=ipv4first --no-network-family-autoselection"
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./package.json
COPY --from=builder /app/next.config.mjs ./next.config.mjs
COPY --from=builder /app/public ./public
EXPOSE 3000
CMD ["npm", "start"]
