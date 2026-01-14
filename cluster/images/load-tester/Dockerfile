# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM grafana/k6:1.2.2@sha256:a98db15eb83dfc4dbded9653a15f53557c011400019eba715a9cd15b0ab709d9

LABEL org.opencontainers.image.base.name="grafana/k6:1.2.2"

# We need root permissions to install packages
USER root

RUN apk add --no-cache curl jq

# Switch back to the non-root k6 user for better security
USER k6


COPY entrypoint.sh .
COPY target/test/* ./
COPY target/LICENSE .

ENTRYPOINT [ "/bin/sh" ]
CMD [ "/home/k6/entrypoint.sh" ]
