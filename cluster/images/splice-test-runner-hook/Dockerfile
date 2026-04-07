ARG RUNNER_VERSION=2.332.0
ARG RUNNER_DIGEST=sha256:8c3f5970b8ceb90cbd3e89b80c6806bb74d9c31686e9177c743323a4539d12f5

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${RUNNER_VERSION}@${RUNNER_DIGEST}

# required to get the org.opencontainers.image.base.name label to render correctly
ARG RUNNER_VERSION

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${RUNNER_VERSION}"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
