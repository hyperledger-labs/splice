# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

$(dir)/$(docker-build) : $(canton-image)/$(docker-build)
$(dir)/$(docker-push) : $(canton-image)/$(docker-push)
