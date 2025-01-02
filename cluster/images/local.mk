# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

images := \
	canton \
	canton-participant \
	canton-domain \
	canton-sequencer \
	canton-mediator \
	canton-cometbft-sequencer \
	cometbft \
	\
	splice-app \
	splice-debug \
	splice-web-ui \
	sv-app \
	sv-web-ui \
	scan-app \
	scan-web-ui \
	wallet-web-ui \
	validator-app \
	splitwell-app \
	\
	ans-web-ui \
	splitwell-web-ui \
	\
	docs \
	load-tester \
	multi-validator \
	multi-participant \
	pulumi-kubernetes-operator \
	\
	splice-test-postgres \
	splice-test-ci \
	splice-test-cometbft \
	splice-test-temp-runner-hook \

canton-image := cluster/images/canton
splice-image := cluster/images/splice-app
splice-ui-image := cluster/images/splice-web-ui

ifdef CI
    # never use the cache in CI on the master branch
    cache_opt := --no-cache
    platform_opt := --platform=linux/amd64,linux/arm64
else
    # Local builds (which may be on an M1) are explicitly constrained
    # to x86.
    platform_opt := --platform=linux/amd64
endif

docker-build := target/docker.id
docker-push := target/docker.push
docker-local-image-tag := target/local-image-tag
docker-image-tag := target/image-tag

#########
# Per-image targets
#########

# You cannot define implicit phony targets
# so instead we define the phony targets in here.
define DEFINE_PHONY_RULES =
prefix := cluster/images/$(1)

include cluster/images/$(1)/local.mk

# Stop make from deleting version files to get working caching.
.NOTINTERMEDIATE: $$(prefix)/$(docker-image-tag) $$(prefix)/$(docker-local-image-tag)

.PHONY: $$(prefix)/docker-build
$$(prefix)/docker-build: $$(prefix)/$(docker-build)

.PHONY: $$(prefix)/docker-push
$$(prefix)/docker-push: $$(prefix)/$(docker-push)

IMG_REF_EXISTS_ARG ?= ""

.PHONY: $$(prefix)/docker-image-reference-exists
$$(prefix)/docker-image-reference-exists: $$(prefix)/$(docker-image-tag)
	docker-image-reference-exists $$$$(cat $$(abspath $$<)) ${IMG_REF_EXISTS_ARG}

.PHONY: $$(prefix)/get-docker-image-id
$$(prefix)/get-docker-image-id: $$(prefix)/$(docker-image-tag)
	get-docker-image-id $$$$(cat $$(abspath $$<))

.PHONY: $$(prefix)/clean
$$(prefix)/clean:
	-rm -vfr $$(@D)/target

endef # end DEFINE_PHONY_RULES

$(foreach image,$(images),$(eval $(call DEFINE_PHONY_RULES,$(image))))

#########
# docker pattern rules
#########

%/$(docker-local-image-tag): force-update-version
	mkdir -p $(@D)
	overwrite-if-changed $$(basename $$(dirname $(@D))):$(shell get-snapshot-version) $@

%/$(docker-image-tag): force-update-version
	mkdir -p $(@D)
	get-docker-image-reference $$(basename $$(dirname $(@D))) > $@

%/$(docker-build): %/$(docker-local-image-tag) %/Dockerfile
	docker-check-multi-arch
	mkdir -pv $(@D)
	@echo docker build triggered because these files changed: $?
	docker buildx build $(platform_opt) --iidfile $@ $(cache_opt) $(build_arg) -t $$(cat $<) $(@D)/..

%/$(docker-push):  %/$(docker-image-tag) %/$(docker-build)
	cd $(@D)/.. && docker-push $$(cat $(abspath $<))
