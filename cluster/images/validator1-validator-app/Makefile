# -*- makefile -*-
include ../common.mk

src := Dockerfile validator1-validator.conf directory-service-0.1.0.dar bootstrap.scala
build_arg := --build-arg base_version=$(shell version-gen)

directory-service-0.1.0.dar: ../../../apps/directory/daml/.daml/dist/directory-service-0.1.0.dar
	cp $< $@

$(docker-build): $(src)
