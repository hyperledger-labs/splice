# -*- makefile -*-
include ../common.mk

src := Dockerfile coin.conf target/directory-service-0.1.0.dar bootstrap.scala
build_arg := --build-arg base_version=$(shell version-gen)

target/directory-service-0.1.0.dar: ../../../apps/directory/daml/.daml/dist/directory-service-0.1.0.dar
	mkdir -p $(@D)
	cp $< $@

$(docker-build): $(src)
