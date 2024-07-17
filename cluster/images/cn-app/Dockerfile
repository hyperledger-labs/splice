# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# This Dockerfile is a modified version of Canton's Dockerfile
FROM eclipse-temurin:17-jdk-jammy

# Install screen for running the console in a headless server,
# and curl for getting onboarding secrets.
RUN apt-get update \
   && DEBIAN_FRONTEND=noninteractive apt-get install -y screen curl tini \
   && apt-get clean \
   && rm -rf /var/cache/apt/archives

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# move tarball to a static name
ADD target/cn-node-0.1.0-SNAPSHOT.tar.gz .

COPY target/monitoring.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/LICENSE /app/

RUN ln -s cn-node-0.1.0-SNAPSHOT/bin/cn-node cn-image-bin

# point entrypoint to the cn-node executable
ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
