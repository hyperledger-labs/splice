# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

FROM grafana/k6:0.48.0

COPY entrypoint.sh .
COPY target/test/* ./

ENTRYPOINT [ "/bin/sh" ]
CMD [ "/home/k6/entrypoint.sh" ]
