# Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: 0BSD
ARG BASE_IMAGE

FROM $BASE_IMAGE
USER root
RUN curl -L https://github.com/vadimi/grpc-client-cli/releases/download/v1.24.3/grpc-client-cli_linux_x86_64.tar.gz | tar -C /usr/local/bin -xz
USER nonroot
