# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG canton_version
ARG image_sha256

FROM europe-docker.pkg.dev/da-images/public-all/docker/canton-participant:${canton_version}@${image_sha256}

COPY additional-config.conf /app/
USER root
RUN chown -R nonroot:nonroot /app/additional-config.conf
USER nonroot
