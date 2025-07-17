# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ghcr.io/actions/actions-runner:latest

LABEL org.opencontainers.image.base.name="ghcr.io/actions/actions-runner:latest"
#Ideally, we'd reduce duplication between this and splice-test-ci, but we're not tackling that right now

RUN sudo apt-get update && \
    sudo apt-get install -y sudo git curl xz-utils pigz rsync jq unzip python3-pip moreutils && \
    sudo rm -rf /var/lib/apt/lists/*

RUN sudo pip3 install GitPython gql humanize marshmallow-dataclass requests requests_toolbelt prometheus_client

RUN sudo ln -s /usr/bin/python3 /usr/bin/python

ENV COURSIER_CACHE=/cache/coursier

COPY target/LICENSE .
COPY wait-docker-and-run.sh /home/runner/wait-docker-and-run.sh
