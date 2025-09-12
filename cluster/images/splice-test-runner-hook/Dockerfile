# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:2.328.0@sha256:db0dcae6d28559e54277755a33aba7d0665f255b3bd2a66cdc5e132712f155e0

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:2.328.0"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
