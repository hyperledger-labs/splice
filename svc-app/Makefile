# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

all: docker-build

src := Dockerfile svc.conf
build_arg := --build-arg base_version=$(shell version-gen)

$(docker-build): $(src)
