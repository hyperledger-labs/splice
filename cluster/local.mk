# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

include cluster/images/local.mk
include cluster/helm/local.mk
# The `-` prefix silently ignores a missing pulumi local.mk, which for now is not part of the Splice dump
-include cluster/pulumi/local.mk

#########
# Toplevel targets
#########

.PHONY: docker-build
docker-build: $(foreach image,$(images),cluster/images/$(image)/$(docker-build))

.PHONY: docker-push
docker-push: $(foreach image,$(images),cluster/images/$(image)/$(docker-push))

.PHONY: docker-scan
docker-scan: $(foreach image,$(images),cluster/images/$(image)/$(docker-scan))

.PHONY: docker-image-reference-exists
docker-image-reference-exists: $(foreach image,$(images),cluster/images/$(image)/docker-image-reference-exists)

.PHONY: cluster/images/clean
cluster/images/clean: $(foreach image,$(images),cluster/images/$(image)/clean)

.PHONY: cluster/helm-push
helm-push: cluster/helm/push

.PHONY: cluster/build
cluster/build: cluster/pulumi/build cluster/helm/build

.PHONY: cluster/format
cluster/format: cluster/pulumi/format

.PHONY: cluster/clean
cluster/clean: cluster/images/clean cluster/pulumi/clean cluster/helm/clean

.PHONY: force-update-version
