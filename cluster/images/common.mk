#########
# docker
#########

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
docker-local-image-tag := target/local-image-tag
docker-image-tag := target/image-tag

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

%/$(docker-push):  %/$(docker-image-tag) %/$(docker-build)
	cd $(@D)/.. && docker-push $$(cat $(abspath $<))
