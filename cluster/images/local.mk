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
	gcs-proxy \
	load-tester \
	party-allocator \
	multi-validator \
	multi-participant \
	\
	splice-test-postgres \
	splice-test-ci \
	splice-test-docker-runner \
	splice-test-cometbft \
	splice-test-runner-hook \

canton-image := cluster/images/canton
splice-image := cluster/images/splice-app
sequencer-image := cluster/images/canton-sequencer
splice-ui-image := cluster/images/splice-web-ui
images_file := cluster/images/.images

ifdef CI
    # never use the cache in CI on the master branch
    cache_opt := --no-cache
    platform_opt := --platform=linux/amd64,linux/arm64
    repo = $$(sed -E  "s/^git@(.*)\:(.*).git/https:\/\/\1\/\2/g" <<< $(CIRCLE_REPOSITORY_URL))
    commit_sha = $(CIRCLE_SHA1)
else
    # Local builds (which may be on an M1) are explicitly constrained
    # to x86.
    platform_opt := --platform=linux/amd64
    repo = "https://github.com/digital-asset/decentralized-canton-sync-dev"
    commit_sha = NoShaForLocalBuild
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

.PHONY: $$(prefix)/docker-scan
$$(prefix)/docker-scan: $$(prefix)/$(docker-scan)

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
	docker-check-env-vars
	mkdir -pv $(@D)
	@echo docker build triggered because these files changed: $?
	docker buildx build $(platform_opt) \
		--label "org.opencontainers.image.ref.name=$$(basename $$(dirname $(@D)))" \
		--label "org.opencontainers.image.version=$(shell get-snapshot-version)" \
		--label "org.opencontainers.image.source=$(repo)" \
		--label "org.opencontainers.image.revision=$(commit_sha)" \
		--iidfile $@ $(cache_opt) $(build_arg) -t $$(cat $<) $(@D)/..

%/$(docker-push):  %/$(docker-image-tag) %/$(docker-build)
	cd $(@D)/.. && docker-push $$(cat $(abspath $<))

%/$(docker-scan):  %/$(docker-image-tag)
	cd $(@D) && docker-scan $$(cat $(abspath $<))

%/$(docker-copy-release-to-ghcr):  %/$(docker-image-tag) %/$(docker-build)
	cd $(@D)/.. && copy_release_to_ghcr $$(cat $(abspath $<))

#########
# Global targets
#########

.PHONY: write-images
write-images:
	overwrite-if-changed '$(shell echo $(images) | tr ' ' '\n')' $(images_file)

.PHONY: cluster/docker/copy_release_to_ghcr
cluster/docker/copy_release_to_ghcr: write-images
	./build-tools/copy_release_images_to_ghcr.sh -v '$(shell get-snapshot-version)' -f $(images_file)
