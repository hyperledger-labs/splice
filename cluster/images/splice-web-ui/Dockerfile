# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM nginx:1.28.0@sha256:e2d0edf0e20951a190fe2322e27c6d790c675e9ff0018ba493947972f816f567

RUN apt-get update \
   && apt-get install -y tini \
   && apt-get clean \
   && rm -rf /var/lib/apt/lists/*

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/usr/bin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD service nginx status || exit 1
