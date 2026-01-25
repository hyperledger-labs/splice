# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG cometbft_version
ARG cometbft_sha
FROM digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:$cometbft_version@sha256:$cometbft_sha

ARG cometbft_version
LABEL org.opencontainers.image.base.name="digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:$cometbft_version"

COPY configure-state-sync.sh /cometbft/
RUN chmod +x /cometbft/configure-state-sync.sh

COPY target/LICENSE .
