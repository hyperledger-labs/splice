# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

# replace absolute paths to helm charts with relative path and sort array by (name, type)
JQ_FILTER := '(.. | .chart? | strings) |= sub("^/.*?(?=/cluster/helm/)"; "") | sort_by("\(.name)|\(.type)")'

include $(PULUMI_TEST_DIR)/pulumi-test-clusters.mk
