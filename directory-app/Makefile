# -*- makefile -*-
include ../build-tools/common.mk

src := Dockerfile directory.conf directory-service-0.1.0.dar bootstrap.scala
build_arg := --build-arg base_version=$(shell version-gen)

.PHONY: build
build: $(docker-build)
directory-service-0.1.0.dar: ../apps/directory/daml/.daml/dist/directory-service-0.1.0.dar
	cp $< $@

$(docker-build): $(src)

.PHONY: clean
clean:
	-rm -vfr target
