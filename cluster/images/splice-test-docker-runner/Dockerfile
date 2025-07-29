# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:2.326.0@sha256:9c3383600872325f21951a0a1a8ae640361ff5e1abdf1f2900de8ba1cfd915e9

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:2.326.0"
#Ideally, we'd reduce duplication between this and splice-test-ci, but we're not tackling that right now

RUN sudo apt-get update && \
    sudo apt-get install -y sudo git curl xz-utils pigz rsync jq unzip python3-pip moreutils qemu-user-static && \
    sudo rm -rf /var/lib/apt/lists/*

RUN sudo pip3 install GitPython gql humanize marshmallow-dataclass requests requests_toolbelt prometheus_client

RUN sudo ln -s /usr/bin/python3 /usr/bin/python

ENV COURSIER_CACHE=/cache/coursier

COPY target/LICENSE .
RUN mv /home/runner/run.sh /home/runner/run-base.sh
COPY run.sh /home/runner/run.sh
