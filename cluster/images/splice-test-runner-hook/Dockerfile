# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:2.326.0@sha256:9c3383600872325f21951a0a1a8ae640361ff5e1abdf1f2900de8ba1cfd915e9

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:2.326.0"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
