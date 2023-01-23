# -*- makefile -*-
include ../common.mk

src := Dockerfile app.conf target/directory-service-0.1.0.dar bootstrap.scala
build_arg := --build-arg base_version=$(shell version-gen)

.PHONY: build
build: $(docker-build)
target/directory-service-0.1.0.dar: ../../../daml/directory-service/.daml/dist/directory-service-0.1.0.dar
	mkdir -p $(@D)
	cp $< $@

$(docker-build): $(src)
