# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# 1.31.6-alpine-slim@sha256:ac4d9c7e457c6510b95ac72dd84b27f7cc68ecb9a83fbd44f05a6460cf08aba4
FROM nginxinc/nginx-unprivileged@sha256:ac4d9c7e457c6510b95ac72dd84b27f7cc68ecb9a83fbd44f05a6460cf08aba4

USER root

RUN apk add --no-cache tini bash

USER nginx

COPY default.conf /etc/nginx/conf.d/

ENTRYPOINT ["/sbin/tini", "--", "/custom-docker-entrypoint.sh"]
HEALTHCHECK CMD nc -z 127.0.0.1 8080 || exit 1
