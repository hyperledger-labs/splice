# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
# xx provides tools to support easy cross-compilation from Dockerfiles, see https://github.com/tonistiigi/xx
FROM --platform=$BUILDPLATFORM tonistiigi/xx AS xx
ARG BUILDPLATFORM
ARG TARGETPLATFORM

ARG base_version
FROM --platform=$BUILDPLATFORM splice-app:${base_version} AS build
ARG BUILDPLATFORM
ARG TARGETPLATFORM

COPY --from=xx / /

RUN xx-apt-get update && \
  xx-apt-get install -y tini

COPY docker-entrypoint.sh /custom-docker-entrypoint.sh
RUN chmod 500 /custom-docker-entrypoint.sh

FROM nginx:stable

COPY --from=build /usr/bin/tini /usr/bin/tini
COPY --from=build /custom-docker-entrypoint.sh /custom-docker-entrypoint.sh
COPY --from=build app/splice-node/web-uis/scan /usr/share/nginx/html/

COPY default.conf /etc/nginx/conf.d
COPY config.js /tmpl/config.js.tmpl

ENTRYPOINT ["/usr/bin/tini", "--", "/custom-docker-entrypoint.sh"]
