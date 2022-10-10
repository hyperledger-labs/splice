# -*- makefile -*-
include ../common.mk

src := Dockerfile svc-participant.conf bootstrap.scala
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
