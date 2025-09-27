# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG BASE_IMAGE="eclipse-temurin:21-jdk-noble@sha256:5a65f334da5a91a66076735d78e3ae30483a2593ac108f830dcd59521f2535cd"

FROM fullstorydev/grpcurl:v1.9.2@sha256:0c3796edc8b6bb1814da2f11a554996237c0dd98380fce182efb1dc44c556ac1 AS grpcurl

FROM ${BASE_IMAGE}

# Make variable available to the build stage
ARG BASE_IMAGE

# Install:
# - screen for running the console in a headless server
# - tini for handling signals and reaping zombie processes
# - libjemalloc2 for debugging memory issues
RUN apt-get update \
   && DEBIAN_FRONTEND=noninteractive apt-get install -y screen tini libjemalloc2 \
   && apt-get clean \
   && rm -rf /var/lib/apt/lists/*

COPY --from=grpcurl /bin/grpcurl /bin/grpcurl

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# move tarball to a static name
ADD target/canton.tar .

COPY target/monitoring.conf target/parameters.conf target/storage.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/logback.xml /app/

# Overwrite the LICENSE.txt file from canton image with the one from Splice
COPY target/LICENSE.txt .

RUN ln -s bin/canton splice-image-bin

# Add label for security scanners, compliance tools, and auditing purposes
LABEL org.opencontainers.image.base.name="${BASE_IMAGE}"

RUN addgroup --gid 1001 nonroot && adduser --uid 1001 --gid 1001 nonroot
RUN chown -R nonroot:nonroot /app
RUN chown nonroot:nonroot splice-image-bin
USER nonroot

# point entrypoint to the amulet executable
ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
