include cluster/manifest/Makefile
include cluster/images/Makefile
include cluster/helm/Makefile
include cluster/pulumi/Makefile

#########
# Toplevel targets
#########

.PHONY: docker-build
docker-build: $(foreach image,$(images),cluster/images/$(image)/$(docker-build))

.PHONY: docker-push
docker-push: $(foreach image,$(images),cluster/images/$(image)/$(docker-push))

.PHONY: docker-push-force
docker-push-force: $(foreach image,$(images),cluster/images/$(image)/docker-push-force)

.PHONY: docker-check
docker-check: $(foreach image,$(images),cluster/images/$(image)/docker-check)

.PHONY: cluster/images/clean
cluster/images/clean: $(foreach image,$(images),cluster/images/$(image)/clean)

.PHONY: cluster/build
cluster/build: cluster/pulumi/install cluster/helm/build

.PHONY: cluster/format
cluster/format: cluster/pulumi/format

.PHONY: cluster/clean
cluster/clean: cluster/images/clean cluster/pulumi/clean cluster/helm/clean

.PHONY: force-update-version
