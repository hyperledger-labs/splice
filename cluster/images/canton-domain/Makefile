# -*- makefile -*-
include ../common.mk

src := Dockerfile canton.conf
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
