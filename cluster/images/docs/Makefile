# -*- makefile -*-
include ../common.mk

src := Dockerfile $(shell find src -type f)

html := target/html/index.html

.PHONY: html
html: $(html)

build_arg := --build-arg version=$(shell version-gen)

$(docker-build): $(html)

$(html): $(src)
	./gen-daml-docs.sh
	VERSION=$(shell version-gen) sphinx-build -M html src target -D version=$(shell version-gen) -W

clean-local:
	rm -vfr src/app_dev/api
