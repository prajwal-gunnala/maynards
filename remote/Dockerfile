FROM node:22-alpine
WORKDIR /srv/mesh-signaling
COPY package.json ./
RUN npm install --omit=dev --ignore-scripts
COPY server.mjs ./
USER node
EXPOSE 3000
CMD ["node", "server.mjs"]
