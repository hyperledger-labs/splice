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


#########################
# skip and ci targets
#########################

image-tag = $(eval image-tag := $$(shell image-tag-gen ${app}))$(image-tag)

.PHONY: docker-check
docker-check:
	docker-check $(image-tag)


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

$(docker-push): $(docker-build)
	$(check-dirty)
	docker-push ${image-tag} | tee $@

.PHONY: docker-push-force
docker-push-force: $(docker-build)
	docker-push ${image-tag} --force | tee $(docker-push)

_did_docker := true
endif
