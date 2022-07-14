# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

.PHONY: all
all: docker-build

src := Dockerfile svc-domain.conf

.PHONY: build
build: $(docker-build)
$(docker-build): $(src)

.PHONY: clean
clean:
	-rm -vfr target
