# Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: 0BSD
ARG BASE_IMAGE

FROM $BASE_IMAGE
USER root
RUN apt-get install --no-install-recommends -y ca-certificates curl \
 && curl -fsSLO https://github.com/fullstorydev/grpcurl/releases/download/v1.8.7/grpcurl_1.8.7_linux_x86_64.tar.gz \
 && curl -fsSLO https://github.com/fullstorydev/grpcurl/releases/download/v1.8.7/grpcurl_1.8.7_checksums.txt \
 && sha256sum --check --ignore-missing grpcurl_1.8.7_checksums.txt \
 && tar -xvf grpcurl_1.8.7_linux_x86_64.tar.gz -C /usr/local/bin --totals grpcurl \
 && rm grpcurl_1.8.7* \
 && apt-get clean && rm -rf /var/cache/apt/archives
USER nonroot
