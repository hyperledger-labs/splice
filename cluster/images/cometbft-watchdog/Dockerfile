# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# 3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1
FROM python@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1

LABEL org.opencontainers.image.base.name="python:3.12-slim"

COPY restart-watchdog.py /restart-watchdog.py

COPY target/LICENSE .

# Ensure logs get output unbuffered even when the output is a pipe and don't write byte code in the container.
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

CMD ["python3", "/restart-watchdog.py"]
