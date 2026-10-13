ARG runner_version
ARG image_sha256

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${runner_version}@${image_sha256}

# required to get the org.opencontainers.image.base.name label to render correctly
ARG runner_version

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${runner_version}"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
