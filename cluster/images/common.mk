# -*- makefile -*-

export MAKE

#########
# common
#########

# only load common once
ifneq ($(_did_common),true)


#######
# vars
#######

app  := $(notdir $(abspath .))
repo_root := $(call must-shell,cd $(dir $(lastword $(MAKEFILE_LIST))).. && pwd)

#######
# utils
#######

# execute a makefile shell but crash make if the command fails
must-shell = $(or $(shell _out="$$($(1))" && echo "$${_out}" || echo "$${_out}" >&2),$(error command '$(1)' failed))

#######
# default build
#######

.PHONY: all
all: docker-build

#######
# clean
#######

.PHONY: clean
clean:
	-rm -vfr target


#########
# docker
#########

docker-src := Dockerfile

#################
## docker images
#################

ifdef CI
    # never use the cache in CI on the master branch
    cache_opt := --no-cache
else
    # Local builds (which may be on an M1) are explicitly constrained
    # to x86.
    platform_opt := --platform=linux/amd64
endif

###############
# docker build
###############

image-tag = $(eval image-tag := $$(shell image-tag-gen ${app}))$(image-tag)
local-image-tag = $(shell echo "${app}:$(shell version-gen)")

docker-build := target/docker.id

.PHONY: docker-build
docker-build: $(docker-build)

$(docker-build): $(docker-src)
	mkdir -pv $(@D)
	@echo docker build triggered because these files changed: $?
	docker build $(platform_opt) --iidfile $@ $(cache_opt) $(build_arg) -t $(local-image-tag) .


##############
# docker push
##############

docker-push := target/docker.push

.PHONY: docker-push
docker-push: $(docker-build)
	docker-push ${image-tag}

.PHONY: docker-push-force
docker-push-force: $(docker-build)
	docker-push ${image-tag} --force

##############
# docker check
##############

.PHONY: docker-check
docker-check:
	docker-check $(image-tag)

_did_common := true
endif
