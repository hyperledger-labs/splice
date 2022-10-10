# -*- makefile -*-
include ../common.mk

target-bundle := target/coin-0.1.0-SNAPSHOT.tar.gz
app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz

src := Dockerfile empty.conf $(target-bundle)

$(docker-build): $(src)

$(target-bundle): $(app-bundle)
	mkdir -p $(@D)
	cp $< $@
