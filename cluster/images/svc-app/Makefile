# -*- makefile -*-
include ../common.mk

src := Dockerfile svc.conf
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
