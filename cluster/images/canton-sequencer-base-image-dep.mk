# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

$(dir)/$(docker-build) : $(sequencer-image)/$(docker-build)
$(dir)/$(docker-push) : $(sequencer-image)/$(docker-push)
