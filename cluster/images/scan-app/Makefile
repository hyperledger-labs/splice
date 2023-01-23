# -*- makefile -*-
include ../common.mk

src := Dockerfile app.conf
build_arg := --build-arg base_version=$(shell version-gen)

.PHONY: build
build: $(docker-build)
$(docker-build): $(src)
