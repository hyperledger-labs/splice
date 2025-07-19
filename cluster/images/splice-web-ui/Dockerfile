# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
# xx provides tools to support easy cross-compilation from Dockerfiles, see https://github.com/tonistiigi/xx
FROM --platform=$BUILDPLATFORM tonistiigi/xx@sha256:923441d7c25f1e2eb5789f82d987693c47b8ed987c4ab3b075d6ed2b5d6779a3 AS xx
ARG BUILDPLATFORM
ARG TARGETPLATFORM

ARG base_version
FROM --platform=$BUILDPLATFORM debian:bookworm-slim@sha256:6ac2c08566499cc2415926653cf2ed7c3aedac445675a013cc09469c9e118fdd AS build
ARG BUILDPLATFORM
ARG TARGETPLATFORM

LABEL org.opencontainers.image.base.name="debian:bookworm-slim"

COPY --from=xx / /

RUN xx-apt-get update && \
  xx-apt-get install -y tini

FROM nginx:1.28.0@sha256:e2d0edf0e20951a190fe2322e27c6d790c675e9ff0018ba493947972f816f567

COPY --from=build /usr/bin/tini /usr/bin/tini
COPY default.conf /etc/nginx/conf.d

ENTRYPOINT ["/usr/bin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD service nginx status || exit 1
