# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

.PHONY: all
all: docker-build

src := Dockerfile svc-participant.conf bootstrap.scala

.PHONY: build
build: $(docker-build)
$(docker-build): $(src)

.PHONY: clean
clean:
	-rm -vfr target
