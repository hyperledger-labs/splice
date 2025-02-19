# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG base_version

FROM canton-sequencer:${base_version}
ENV EXTRA_CLASSPATH=/app/cometbft-driver.jar

COPY target/driver.jar cometbft-driver.jar
