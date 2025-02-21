# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM splice-app:${base_version}

FROM nginx:stable
ARG version

LABEL org.opencontainers.image.base.name="nginx:stable"

COPY --from=0 app/splice-node/docs/html /usr/share/nginx/html/
COPY --from=0 app/LICENSE .
COPY script.js /tmpl/script.js.tmpl
COPY docker-entrypoint.sh /custom-docker-entrypoint.sh
ENTRYPOINT ["/custom-docker-entrypoint.sh"]
