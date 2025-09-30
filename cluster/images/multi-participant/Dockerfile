# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version

FROM canton:${base_version}

ARG base_version
LABEL org.opencontainers.image.base.name="canton:${base_version}"

COPY --chown=nonroot:nonroot pre-bootstrap.sh app.conf /app/
