# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# 1.8.2@sha256:bd6ae8607c0a05d0dc0dc3f5753c464b0929baf083ef0f20b7eed845a3d9e6b4
FROM grafana/k6@sha256:bd6ae8607c0a05d0dc0dc3f5753c464b0929baf083ef0f20b7eed845a3d9e6b4

LABEL org.opencontainers.image.base.name="grafana/k6:1.8.2"

# We need root permissions to install packages
USER root

RUN apk add --no-cache curl jq

# Switch back to the non-root k6 user for better security
USER k6


COPY entrypoint.sh .
COPY target/test/* ./
COPY target/LICENSE .

ENTRYPOINT [ "/bin/sh" ]
CMD [ "/home/k6/entrypoint.sh" ]
