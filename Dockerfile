FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY server.mjs transfers.mjs ./
COPY public ./public
USER node
EXPOSE 3000
CMD ["node", "server.mjs"]
