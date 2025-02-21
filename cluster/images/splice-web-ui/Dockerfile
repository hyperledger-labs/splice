# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
# xx provides tools to support easy cross-compilation from Dockerfiles, see https://github.com/tonistiigi/xx
FROM --platform=$BUILDPLATFORM tonistiigi/xx AS xx
ARG BUILDPLATFORM
ARG TARGETPLATFORM

ARG base_version
FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS build
ARG BUILDPLATFORM
ARG TARGETPLATFORM

LABEL org.opencontainers.image.base.name="debian:bookworm-slim"

COPY --from=xx / /

RUN xx-apt-get update && \
  xx-apt-get install -y tini

FROM nginx:stable

COPY --from=build /usr/bin/tini /usr/bin/tini
COPY default.conf /etc/nginx/conf.d

ENTRYPOINT ["/usr/bin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD service nginx status || exit 1
