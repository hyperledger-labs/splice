# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM nginxinc/nginx-unprivileged:1.29.0@sha256:3161d975277f6af7e171bdccf48e3469cab8e5788fa63767f6656d4bfb49549c

USER root

RUN apt-get update \
   && apt-get install -y tini \
   && apt-get clean \
   && rm -rf /var/lib/apt/lists/*

USER nginx

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/usr/bin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD service nginx status || exit 1
