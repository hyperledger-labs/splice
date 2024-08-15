# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM --platform=linux/amd64 splice-app:${base_version}

FROM nginx:stable
RUN apt-get update && \
    apt-get install -y gettext tini
COPY default.conf /etc/nginx/conf.d
COPY --from=0 app/cn-node-0.1.0-SNAPSHOT/web-uis/sv /usr/share/nginx/html/

COPY config.js /tmpl/config.js.tmpl

COPY docker-entrypoint.sh /custom-docker-entrypoint.sh
RUN chmod 500 /custom-docker-entrypoint.sh
ENTRYPOINT ["/usr/bin/tini", "--", "/custom-docker-entrypoint.sh"]
