# -*- makefile -*-
include ../common.mk

src := Dockerfile coin.conf target/splitwise-0.1.0.dar
build_arg := --build-arg base_version=$(shell version-gen)

target/splitwise-0.1.0.dar: ../../../daml/splitwise/.daml/dist/splitwise-0.1.0.dar
	mkdir -p $(@D)
	cp $< $@

$(docker-build): $(src)
