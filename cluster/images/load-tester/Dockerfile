# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM grafana/k6:0.48.0@sha256:8f3404658de9c66ba4c446c21bf88beeac679fdaacee9245d21d68a5c7930d39

LABEL org.opencontainers.image.base.name="grafana/k6:0.48.0"

COPY entrypoint.sh .
COPY target/test/* ./
COPY target/LICENSE .

ENTRYPOINT [ "/bin/sh" ]
CMD [ "/home/k6/entrypoint.sh" ]
