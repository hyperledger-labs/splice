# -*- makefile -*-
include ../build-common/common.mk
include ../build-common/docker.mk

.PHONY: all
all: docker-images

src := Dockerfile $(shell find src -type f)
$(srcdigest): $(src)

.PHONY: build
build: $(docker-build)
$(docker-build): html

.PHONY: html
html: $(src)
	sphinx-build -M html src target -W

.PHONY: run
run:
	sphinx-autobuild src target

.PHONY: clean
clean:
	rm -rfv target
