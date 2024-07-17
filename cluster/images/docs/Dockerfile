# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM --platform=linux/amd64 cn-app:${base_version}

FROM nginx:stable
ARG version

COPY --from=0 app/cn-node-0.1.0-SNAPSHOT/docs/html /usr/share/nginx/html/
