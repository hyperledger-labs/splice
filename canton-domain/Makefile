# -*- makefile -*-
include ../build-common/common.mk
include ../build-common/docker.mk

.PHONY: all
all: docker-build

src := Dockerfile svc-domain.conf

.PHONY: build
build: $(docker-build)
$(docker-build): $(src)

.PHONY: clean
clean:
	-rm -vfr target
