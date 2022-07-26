# -*- makefile -*-
include ../build-tools/common.mk
include ../build-tools/docker.mk

.PHONY: all
all: docker-build

src := Dockerfile

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz
target-bundle := target/coin-0.1.0-SNAPSHOT.tar.gz

.PHONY: build
build: $(docker-build)
$(docker-build): $(src) $(target-bundle)

$(target-bundle): $(app-bundle)
	mkdir -p $(@D)
	cp $< $@

$(app-bundle):
	cd ${REPO_ROOT} && sbt bundle

.PHONY: clean
clean:
	-rm -vfr target
