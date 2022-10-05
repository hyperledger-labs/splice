# -*- makefile -*-
include ../../../build-tools/common.mk

src := Dockerfile config.js
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
