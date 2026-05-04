# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG canton_version
ARG image_sha256

FROM europe-docker.pkg.dev/da-images/public-all/docker/canton-participant:${canton_version}@${image_sha256}
# TODO(https://github.com/canton-network/splice/issues/5141): remove this once base images have grpc_health_probe
COPY --from=ghcr.io/grpc-ecosystem/grpc-health-probe:v0.4.38 /ko-app/grpc-health-probe /bin/grpc_health_probe

COPY additional-config.conf /app/
COPY target/logback.xml /app/
COPY R__repeatable-migration.sql /app/repeatable-migrations/
USER root
# TODO(https://github.com/canton-network/splice/issues/5141): remove this once base images have grpc_health_probe
RUN rm -f /bin/grpcurl
RUN chown -R nonroot:nonroot /app/additional-config.conf /app/repeatable-migrations/R__repeatable-migration.sql
USER nonroot

HEALTHCHECK --start-period=10m \
  CMD grpc_health_probe -addr=:5061 || exit 1