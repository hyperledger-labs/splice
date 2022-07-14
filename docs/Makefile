# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

.PHONY: all
all: docker-build

src := Dockerfile $(shell find src -type f)

html := target/html/index.html

.PHONY: build
build: $(docker-build)
$(docker-build): $(html)

.PHONT: html
html: $(html)

$(html): $(src)
	sphinx-build -M html src target -W

.PHONY: run
run:
	sphinx-autobuild src target

.PHONY: clean
clean:
	rm -rfv target
