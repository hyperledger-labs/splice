# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

ARG cometbft_version
FROM digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:$cometbft_version
COPY configure-state-sync.sh /cometbft/
RUN chmod +x /cometbft/configure-state-sync.sh
