# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version

FROM splice-app:${base_version}

ARG base_version
LABEL org.opencontainers.image.base.name="splice-app:${base_version}"

COPY --chown=nonroot:nonroot app.conf pre-bootstrap.sh health-check.sh /app/
