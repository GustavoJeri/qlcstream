FROM node:26-alpine AS build
WORKDIR /workspace
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
ARG QLCSTREAM_VERSION=0.1.0-rc.1
ARG QLCSTREAM_COMMIT=unknown
LABEL org.opencontainers.image.title="QLC Stream Frontend" \
      org.opencontainers.image.version="${QLCSTREAM_VERSION}" \
      org.opencontainers.image.revision="${QLCSTREAM_COMMIT}"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
