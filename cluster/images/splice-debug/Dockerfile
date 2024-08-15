# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM ubuntu:latest
RUN apt-get update && apt-get install -y postgresql-client curl
RUN curl -sSLO https://github.com/fullstorydev/grpcurl/releases/download/v1.9.1/grpcurl_1.9.1_linux_amd64.deb && dpkg -i grpcurl_1.9.1_linux_amd64.deb && rm grpcurl_1.9.1_linux_amd64.deb
