# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

include cluster/images/splice-base-image-dep.mk

$(dir)/$(docker-build) : $(splice-ui-image)/$(docker-build)
$(dir)/$(docker-push) : $(splice-ui-image)/$(docker-push)
