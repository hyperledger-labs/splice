# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM node:22-alpine-3.21@sha256:6b2127043c2faa4f15cdcdeb65a39fc9afbecf5559301898b754bbff561a8aa9

LABEL org.opencontainers.image.base.name="node/22-alpine-3.21"

# to get envsubst
RUN apk update && apk add gettext

COPY entrypoint.sh .
COPY target/bundle.js ./party-allocator/
COPY target/LICENSE .

ENTRYPOINT [ "/bin/sh" ]
CMD [ "entrypoint.sh" ]
