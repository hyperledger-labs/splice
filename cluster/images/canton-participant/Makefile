# -*- makefile -*-
include ../common.mk

src := Dockerfile app.conf bootstrap.scala
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
