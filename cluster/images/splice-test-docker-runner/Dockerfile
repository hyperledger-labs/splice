ARG RUNNER_VERSION=2.328.0
ARG RUNNER_DIGEST=sha256:db0dcae6d28559e54277755a33aba7d0665f255b3bd2a66cdc5e132712f155e0

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${RUNNER_VERSION}@${RUNNER_DIGEST}

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${RUNNER_VERSION}"
#Ideally, we'd reduce duplication between this and splice-test-ci, but we're not tackling that right now

RUN sudo apt-get update && \
    sudo apt-get install -y sudo git curl xz-utils pigz rsync jq unzip python3-pip moreutils && \
    sudo rm -rf /var/lib/apt/lists/*

RUN sudo pip3 install GitPython gql humanize marshmallow-dataclass requests requests_toolbelt prometheus_client

RUN sudo ln -s /usr/bin/python3 /usr/bin/python

ENV COURSIER_CACHE=/cache/coursier

COPY target/LICENSE .
