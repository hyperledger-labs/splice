# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

all: docker-build

src := Dockerfile $(shell find src -type f)

html := target/html/index.html

.PHONY: html
html: $(html)

$(docker-build): $(html)

$(html): $(src)
	./gen-daml-docs.sh
	sphinx-build -M html src target -W
