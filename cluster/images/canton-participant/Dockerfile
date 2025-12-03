# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# ARG base_version
# FROM canton:${base_version}

# ARG base_version
# LABEL org.opencontainers.image.base.name=canton:${base_version}

# EXPOSE 5001
# EXPOSE 5002
# EXPOSE 7575
# EXPOSE 10013

# COPY app.conf /app/

# HEALTHCHECK --start-period=10m \
#   CMD grpcurl -plaintext localhost:5061 grpc.health.v1.Health/Check || exit 1

FROM europe-docker.pkg.dev/da-images/public-unstable/docker/canton-participant:3.4.10-ad-hoc.20251203.17443.0.v217cc2a7
