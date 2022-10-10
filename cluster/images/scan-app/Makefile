# -*- makefile -*-
include ../common.mk

src := Dockerfile scan.conf
build_arg := --build-arg base_version=$(shell version-gen)

.PHONY: build
build: $(docker-build)
$(docker-build): $(src)

.PHONY: clean
clean:
	-rm -vfr target
