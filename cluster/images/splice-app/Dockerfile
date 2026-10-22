# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# full-1.0.17@sha256:61d41e5cd9ea35dd386b05251b8b8d7362736821d542cd6c07d76c7317e817f3
FROM europe-docker.pkg.dev/da-images/public/docker/da-base-image@sha256:61d41e5cd9ea35dd386b05251b8b8d7362736821d542cd6c07d76c7317e817f3

WORKDIR /app

# move tarball to a static name
ADD target/splice-node.tar.gz .

COPY target/storage.conf target/monitoring.conf target/parameters.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/logback.xml target/LICENSE /app/

USER root
RUN mkdir -p /app/splice-node/docs/html/cn-release-bundles

RUN ln -s splice-node/bin/splice-node splice-image-bin

RUN chown nonroot:nonroot splice-image-bin
USER nonroot

# point entrypoint to the splice-node executable
ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
