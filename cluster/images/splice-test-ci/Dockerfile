# Note that we don't currently support arm64 runners, so we build this only for amd64
FROM --platform=$BUILDPLATFORM ubuntu:24.04

RUN apt-get update && \
    apt-get install -y sudo git curl xz-utils pigz rsync jq && \
    rm -rf /var/lib/apt/lists/*

RUN groupadd --gid=1002 ci && \
    mkdir /github && \
    useradd --uid=1001 --gid=ci --home-dir /github/home --create-home ci && \
    echo 'ci ALL=NOPASSWD: ALL' >> /etc/sudoers.d/50-ci && \
    echo 'Defaults    env_keep += "DEBIAN_FRONTEND"' >> /etc/sudoers.d/env_keep && \
    sudo -u ci mkdir /github/home/project && \
    sudo -u ci mkdir /github/home/bin && \
    sudo -u ci mkdir -p /github/home/.local/bin

USER ci
RUN whoami && \
	# opt-out of the new security feature, not needed in a CI environment
	git config --global --add safe.directory '*'

ENV COURSIER_CACHE=/cache/coursier

WORKDIR /github/home/project
