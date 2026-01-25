# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

# sort array by (name, type)
JQ_FILTER := 'sort_by("\(.name)|\(.type)")'

include $(PULUMI_TEST_DIR)/pulumi-test-clusters.mk
