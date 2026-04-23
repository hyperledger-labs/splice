# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG canton_version
ARG image_sha256

FROM europe-docker.pkg.dev/da-images/public-all/docker/canton-base:${canton_version}@${image_sha256}

COPY additional-config.conf /app/
USER root
RUN rm -f /bin/grpcurl
RUN curl -L https://github.com/vadimi/grpc-client-cli/releases/download/v1.24.3/grpc-client-cli_linux_x86_64.tar.gz | tar -C /usr/local/bin -xz
RUN chown -R nonroot:nonroot /app/additional-config.conf
USER nonroot
