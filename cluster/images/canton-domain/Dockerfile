# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM canton:${base_version}

ARG base_version
LABEL org.opencontainers.image.base.name="canton:${base_version}"

EXPOSE 5007
EXPOSE 5008
EXPOSE 5009
EXPOSE 5061
EXPOSE 5062
EXPOSE 10013

COPY app.conf bootstrap.sc /app/

HEALTHCHECK --start-period=10m \
  CMD grpcurl -plaintext localhost:5061 grpc.health.v1.Health/Check && grpcurl -plaintext localhost:5062 grpc.health.v1.Health/Check || exit 1
