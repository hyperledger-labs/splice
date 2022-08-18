# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

all: docker-build

src := Dockerfile $(shell find conf -name '*.conf' -type f)

$(docker-build): $(src)
