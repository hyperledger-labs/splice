# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# xx provides tools to support easy cross-compilation from Dockerfiles, see https://github.com/tonistiigi/xx
FROM --platform=$BUILDPLATFORM tonistiigi/xx AS xx
ARG BUILDPLATFORM
ARG TARGETPLATFORM

FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS build

COPY --from=xx / /
ARG BUILDPLATFORM
ARG TARGETPLATFORM

# Install screen for running the console in a headless server
RUN xx-apt-get update \
   && DEBIAN_FRONTEND=noninteractive xx-apt-get install -y screen tini \
   && xx-apt-get clean \
   && rm -rf /var/cache/apt/archives

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# move tarball to a static name
ADD target/canton.tar .

COPY target/monitoring.conf target/parameters.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/logback.xml /app/

RUN ln -s bin/canton splice-image-bin

FROM fullstorydev/grpcurl:v1.9.2 AS grpcurl

FROM eclipse-temurin:17-jdk-jammy
COPY --from=build /usr/bin/tini /usr/bin/tini
COPY --from=build /usr/bin/screen /usr/bin/screen
COPY --from=grpcurl /bin/grpcurl /bin/grpcurl
COPY --from=build /app/ /app/

WORKDIR /app

# point entrypoint to the amulet executable
ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
