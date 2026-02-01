ARG RUNNER_VERSION=2.331.0
ARG RUNNER_DIGEST=sha256:dced476aa42703ebd9aafc295ce52f160989c4528e831fc3be2aef83a1b3f6da

# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:${RUNNER_VERSION}@${RUNNER_DIGEST}

# required to get the org.opencontainers.image.base.name label to render correctly
ARG RUNNER_VERSION

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:${RUNNER_VERSION}"
#Ideally, we'd reduce duplication between this and splice-test-ci, but we're not tackling that right now

RUN sudo apt-get update && \
    sudo apt-get install -y sudo git curl xz-utils pigz rsync jq unzip python3-pip moreutils && \
    sudo rm -rf /var/lib/apt/lists/*

RUN sudo pip3 install GitPython gql humanize marshmallow-dataclass requests requests_toolbelt prometheus_client

RUN sudo ln -s /usr/bin/python3 /usr/bin/python

ENV COURSIER_CACHE=/cache/coursier

COPY target/LICENSE .
