# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# We don't currently have a need for multi-architecture support in this image,
# so we're hard-coding amd64, and accepting that this will break on arm64.
# check=skip=FromPlatformFlagConstDisallowed
FROM --platform=linux/amd64 ubuntu:26.04@sha256:f144425ff09be612d6d9ad965196e9cdc23dae1f42110a8a11a3e9a8198759f7
LABEL org.opencontainers.image.base.name="ubuntu:26.04"

ARG KUBECTL_VERSION

RUN apt-get update && apt-get install -y \
    postgresql-client \
    curl \
    netcat-openbsd \
    jq

RUN curl -fL -LO https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl && install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
RUN curl -sSLO https://github.com/fullstorydev/grpcurl/releases/download/v1.9.2/grpcurl_1.9.2_linux_amd64.deb && dpkg -i grpcurl_1.9.2_linux_amd64.deb && rm grpcurl_1.9.2_linux_amd64.deb

COPY target/LICENSE .
