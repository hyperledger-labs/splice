# -*- makefile -*-
include ../../../build-tools/common.mk

src := Dockerfile validator1-participant.conf bootstrap.scala
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
