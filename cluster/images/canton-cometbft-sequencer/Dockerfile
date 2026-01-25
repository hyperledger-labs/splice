# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version
FROM canton-sequencer:${base_version}

ARG base_version
LABEL org.opencontainers.image.base.name="canton:${base_version}"

EXPOSE 5008
EXPOSE 5009
EXPOSE 10013
ENV EXTRA_CLASSPATH=/app/cometbft-driver.jar

COPY target/driver.jar cometbft-driver.jar
