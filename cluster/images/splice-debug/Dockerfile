# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# We don't currently have a need for multi-architecture support in this image,
# so we're hard-coding amd64, and accepting that this will break on arm64.
FROM --platform=linux/amd64 ubuntu:latest
RUN apt-get update && apt-get install -y postgresql-client curl
RUN curl -sSLO https://github.com/fullstorydev/grpcurl/releases/download/v1.9.1/grpcurl_1.9.1_linux_amd64.deb && dpkg -i grpcurl_1.9.1_linux_amd64.deb && rm grpcurl_1.9.1_linux_amd64.deb
