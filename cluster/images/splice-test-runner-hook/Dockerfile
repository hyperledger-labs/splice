# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:2.330.0@sha256:ee54ad8776606f29434f159196529b7b9c83c0cb9195c1ff5a7817e7e570dcfe

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:2.230.0"

COPY target/index.js /home/runner/k8s/index.js
COPY target/LICENSE .
