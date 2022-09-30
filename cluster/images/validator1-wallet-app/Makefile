# -*- makefile -*-
include ../../../build-tools/common.mk

src := Dockerfile validator1-wallet.conf
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
