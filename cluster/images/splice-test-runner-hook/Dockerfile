ARG RUNNER_VERSION=2.331.0
ARG RUNNER_DIGEST=sha256:dced476aa42703ebd9aafc295ce52f160989c4528e831fc3be2aef83a1b3f6da

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${RUNNER_VERSION}@${RUNNER_DIGEST}

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${RUNNER_VERSION}"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
