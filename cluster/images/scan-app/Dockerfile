# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version

FROM splice-app:${base_version}

ARG base_version
LABEL org.opencontainers.image.base.name="splice-app:${base_version}"

EXPOSE 5012
EXPOSE 10013

COPY app.conf /app/

HEALTHCHECK --start-period=10m \
  CMD curl -f http://localhost:5012/api/scan/readyz || exit 1
