images := \
	canton \
	canton-participant \
	canton-domain \
	splitwise-participant \
	validator1-participant \
	\
	cn-app \
	sv-app \
	svc-app \
	scan-app \
	directory-app \
	validator1-validator-app \
	validator1-wallet-app \
	splitwise-validator-app \
	splitwise-app \
	\
	validator1-wallet-web-ui \
	validator1-directory-web-ui \
	validator1-splitwise-web-ui \
	\
	docs \
	external-proxy \
	gcs-proxy \
	envoy-proxy

canton-image-build := cluster/images/canton/$(docker-build)
canton-image-push := cluster/images/canton/$(docker-push)

cn-image-build := cluster/images/cn-app/$(docker-build)
cn-image-push := cluster/images/cn-app/$(docker-push)

$(foreach image,$(images),$(eval include cluster/images/$(image)/Makefile))
include cluster/manifest/Makefile

.PHONY: docker-build
docker-build: $(foreach image,$(images),cluster/images/$(image)/$(docker-build))

.PHONY: docker-push
docker-push: $(foreach image,$(images),cluster/images/$(image)/$(docker-push))

.PHONY: docker-push-force
docker-push-force: $(foreach image,$(images),cluster/images/$(image)/docker-push-force)

.PHONY: docker-check
docker-check: $(foreach image,$(images),cluster/images/$(image)/docker-check)

.PHONY: force-update-version

# You cannot define implicit phony targets
# so instead we define the phony targets in here.
define DEFINE_PHONY_RULES =
prefix := cluster/images/$(1)

# Stop make from deleting version files to get working caching.
.NOTINTERMEDIATE: $$(prefix)/$(docker-image-tag) $$(prefix)/$(docker-local-image-tag)

.PHONY: $$(prefix)/docker-push-force
$$(prefix)/docker-push-force: $$(prefix)/$(docker-image-tag) $$(prefix)/$(docker-build)
	cd $$(@D) && docker-push $$$$(cat $$(abspath $$<)) --force

.PHONY: $$(prefix)/docker-build
$$(prefix)/docker-build: $$(prefix)/$(docker-build)

.PHONY: $$(prefix)/docker-push
$$(prefix)/docker-push: $$(prefix)/$(docker-push)

.PHONY: $$(prefix)/docker-check
$$(prefix)/docker-check: $$(prefix)/$(docker-image-tag)
	docker-check $$$$(cat $$(abspath $$<))

.PHONY: $$(prefix)/clean
$$(prefix)/clean:
	-rm -vfr $$(@D)/target
endef

$(foreach image,$(images),$(eval $(call DEFINE_PHONY_RULES,$(image))))
