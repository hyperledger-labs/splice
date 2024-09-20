# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM splice-app:${base_version} AS splice

ARG base_version
FROM splice-web-ui:${base_version}

COPY --chmod=500 docker-entrypoint.sh /custom-docker-entrypoint.sh
COPY config.js /tmpl/config.js.tmpl
COPY --from=splice app/splice-node/web-uis/sv /usr/share/nginx/html/
