# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM splice-app:${base_version} AS splice

ARG base_version
FROM splice-web-ui:${base_version}

ARG base_version
LABEL org.opencontainers.image.base.name="splice-web-ui:${base_version}"

COPY --from=splice app/splice-node/web-uis/ans /usr/share/nginx/html/
COPY config.js /tmpl/config.js.tmpl
COPY --chmod=500 docker-entrypoint.sh /custom-docker-entrypoint.sh

USER root
RUN chown -R nginx:nginx /tmpl
RUN chown nginx:nginx /custom-docker-entrypoint.sh
RUN chown -R nginx:nginx /usr/share/nginx/html
USER nginx
