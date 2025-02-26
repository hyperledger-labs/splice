# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# xx provides tools to support easy cross-compilation from Dockerfiles, see https://github.com/tonistiigi/xx
FROM --platform=$BUILDPLATFORM tonistiigi/xx AS xx
ARG BUILDPLATFORM
ARG TARGETPLATFORM

FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS build

LABEL org.opencontainers.image.base.name="debian:bookworm-slim"

COPY --from=xx / /
ARG BUILDPLATFORM
ARG TARGETPLATFORM

# Install screen for running the console in a headless server,
RUN xx-apt-get update \
   && DEBIAN_FRONTEND=noninteractive xx-apt-get install -y screen tini \
   && xx-apt-get clean \
   && rm -rf /var/cache/apt/archives

# Note that we do not currently install curl, since it's harder to copy to the following stages.
# We cuse wget instead, which suffices for now. If we end up needing curl, we should consider using
# https://github.com/moparisthebest/static-curl.

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# move tarball to a static name
ADD target/splice-node.tar.gz .

COPY target/storage.conf target/monitoring.conf target/parameters.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/logback.xml target/LICENSE /app/
RUN mkdir -p /app/splice-node/docs/html/cn-release-bundles

RUN ln -s splice-node/bin/splice-node splice-image-bin

# This Dockerfile is a modified version of Canton's Dockerfile
FROM eclipse-temurin:17-jdk-jammy
COPY --from=build /usr/bin/tini /usr/bin/tini
COPY --from=build /usr/bin/screen /usr/bin/screen
COPY --from=build /app/ /app/

WORKDIR /app

# point entrypoint to the splice-node executable
ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
