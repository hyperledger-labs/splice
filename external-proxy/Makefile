# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

.PHONY: all
all: docker-build

.PHONY: build
build: $(docker-build)
$(docker-build): $(html)

.PHONY: clean
clean:
	rm -rfv target
