# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# 22.23.3-alpine3.24@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402
FROM node@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402

LABEL org.opencontainers.image.base.name="node:22.23.3-alpine3.24"

# to get envsubst
RUN apk update && apk add gettext

COPY entrypoint.sh .
COPY target/bundle.js ./party-allocator/
COPY target/LICENSE .

ENTRYPOINT [ "/bin/sh" ]
CMD [ "entrypoint.sh" ]
