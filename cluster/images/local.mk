images := \
	canton \
	canton-participant \
	canton-domain \
	\
	cn-app \
	sv-app \
	sv-web-ui \
	svc-app \
	scan-app \
	scan-web-ui \
	directory-app \
	wallet-web-ui \
	validator-app \
	splitwell-app \
	\
	directory-web-ui \
	splitwell-web-ui \
	\
	docs \
	external-proxy \
	gcs-proxy \
	envoy-proxy \
	json-api

canton-image := cluster/images/canton
cn-image := cluster/images/cn-app

ifdef CI
    # never use the cache in CI on the master branch
    cache_opt := --no-cache
else
    # Local builds (which may be on an M1) are explicitly constrained
    # to x86.
    platform_opt := --platform=linux/amd64
endif

docker-build := target/docker.id
docker-push := target/docker.push
docker-promote := target/docker.promote
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

.PHONY: $$(prefix)/docker-push-force
$$(prefix)/docker-push-force: $$(prefix)/$(docker-image-tag) $$(prefix)/$(docker-build)
	cd $$(@D) && docker-push $$$$(cat $$(abspath $$<)) --force

.PHONY: $$(prefix)/docker-build
$$(prefix)/docker-build: $$(prefix)/$(docker-build)

.PHONY: $$(prefix)/docker-push
$$(prefix)/docker-push: $$(prefix)/$(docker-push)

.PHONY: $$(prefix)/docker-promote
$$(prefix)/docker-promote: $$(prefix)/$(docker-promote)

.PHONY: $$(prefix)/docker-check
$$(prefix)/docker-check: $$(prefix)/$(docker-image-tag)
	docker-check $$$$(cat $$(abspath $$<))

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
	overwrite-if-changed $$(basename $$(dirname $(@D))):$(shell version-gen) $@

%/$(docker-image-tag): force-update-version
	mkdir -p $(@D)
	image-tag-gen $$(basename $$(dirname $(@D))) > $@

%/$(docker-build): %/$(docker-local-image-tag) %/Dockerfile
	mkdir -pv $(@D)
	@echo docker build triggered because these files changed: $?
	docker build $(platform_opt) --iidfile $@ $(cache_opt) $(build_arg) -t $$(cat $<) $(@D)/..

ifdef OVERWRITE_DOCKER_IMAGE
%/$(docker-push):  %/$(docker-image-tag) %/$(docker-build)
	cd $(@D)/.. && docker-push $$(cat $(abspath $<)) --force
else
%/$(docker-push):  %/$(docker-image-tag) %/$(docker-build)
	cd $(@D)/.. && docker-push $$(cat $(abspath $<))
endif

%/$(docker-promote):  %/$(docker-image-tag)
	cd $(@D)/.. && docker-promote $$(cat $(abspath $<)) $(shell image-tag-gen $$(basename $$(dirname $(@D))) --artifactory)
