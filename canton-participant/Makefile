# -*- makefile -*-
include ../build-common/common.mk
include ../build-common/docker.mk

.PHONY: all
all: docker-build

src := Dockerfile svc-participant.conf bootstrap.scala
$(srcdigest): $(src)

.PHONY: build
build: $(docker-build)
$(docker-build): $(src)

.PHONY: clean
clean:
	-rm -vfr target
