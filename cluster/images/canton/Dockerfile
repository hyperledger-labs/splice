# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# xx provides tools to support easy cross-compilation from Dockerfiles, see https://github.com/tonistiigi/xx
FROM --platform=$BUILDPLATFORM tonistiigi/xx@sha256:923441d7c25f1e2eb5789f82d987693c47b8ed987c4ab3b075d6ed2b5d6779a3 AS xx
ARG BUILDPLATFORM
ARG TARGETPLATFORM

FROM --platform=$BUILDPLATFORM debian:bookworm-slim@sha256:6ac2c08566499cc2415926653cf2ed7c3aedac445675a013cc09469c9e118fdd AS build

COPY --from=xx / /
ARG BUILDPLATFORM
ARG TARGETPLATFORM

# Install screen for running the console in a headless server
RUN xx-apt-get update \
   && DEBIAN_FRONTEND=noninteractive xx-apt-get install -y screen tini libjemalloc2 \
   && xx-apt-get clean \
   && rm -rf /var/cache/apt/archives

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# move tarball to a static name
ADD target/canton.tar .

COPY target/monitoring.conf target/parameters.conf target/storage.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/logback.xml /app/

# Overwrite the LICENSE.txt file from canton image with the one from Splice
COPY target/LICENSE.txt .

RUN ln -s bin/canton splice-image-bin

FROM fullstorydev/grpcurl:v1.9.2@sha256:0c3796edc8b6bb1814da2f11a554996237c0dd98380fce182efb1dc44c556ac1 AS grpcurl

FROM eclipse-temurin:21-jdk-noble@sha256:5a65f334da5a91a66076735d78e3ae30483a2593ac108f830dcd59521f2535cd

COPY --from=build /usr/bin/tini /usr/bin/tini
COPY --from=build /usr/bin/screen /usr/bin/screen
COPY --from=grpcurl /bin/grpcurl /bin/grpcurl
COPY --from=build /app/ /app/

WORKDIR /app

LABEL org.opencontainers.image.base.name="debian:bookworm-slim"
# point entrypoint to the amulet executable
ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
