# -*- makefile -*-


#########
# docker
#########

# only load docker once
ifneq ($(_did_docker),true)


docker-compose-files = -f bats/docker-compose.yml$(if $(wildcard docker-compose.override.yml), -f docker-compose.override.yml)
docker-src := Dockerfile

#################
## docker images
#################

ifeq ($(CI),true)
    # never use the cache in CI on the master branch
    cache_opt := --no-cache
else
    # Local builds (which may be on an M1) are explicitly constrained
    # to x86.
    platform_opt := --platform=linux/amd64
endif


docker-images := target/docker.images

.PHONY: docker-images
docker-images: $(docker-images)
$(docker-images): $(if $(EXTRA_TAGS),FORCE) $(repo_root)/build-common/registries
	mkdir -pv $(@D)
	@printf '%s\n' \
		$(foreach registry,$(call must-shell,cat $(lastword $^)),\
			$(foreach tag,$(addprefix $(tag-prefix),$(call must-shell, version-gen) $(EXTRA_TAGS)),\
				$(registry)/$(app):$(tag)\
			)\
		) | tee $@


#########################
# skip and ci targets
#########################


.PHONY: docker-check
docker-check: $(docker-images)
	docker-check $(call must-shell,cat $<)


###############
# docker build
###############

docker-build := target/docker.id

.PHONY: docker-build
docker-build: $(docker-build)

$(docker-build): $(docker-src)
	mkdir -pv $(@D)
	@echo docker build triggered because these files changed: $?
	docker build $(platform_opt) --pull --iidfile $@ $(cache_opt) .


##############
# docker push
##############

docker-push := target/docker.push

.PHONY: docker-push
docker-push: $(docker-push)

# delete target/docker.images if EXTRA_TAGS was given to force it to rebuild next time
$(docker-push): $(docker-images) $(docker-build)
	$(check-dirty)
	docker-push | tee $@
	$(if $(EXTRA_TAGS),rm -v $<)


_did_docker := true
endif
