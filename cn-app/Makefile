# -*- makefile -*-
include ../build-tools/common.mk

src := Dockerfile empty.conf

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz
target-bundle := target/coin-0.1.0-SNAPSHOT.tar.gz

$(docker-build): $(src) $(target-bundle)

$(target-bundle): $(app-bundle)
	mkdir -p $(@D)
	cp $< $@

$(app-bundle):
	cd ${REPO_ROOT} && sbt bundle

.PHONY: clean-sbt
clean: clean-sbt

clean-sbt:
	cd ${REPO_ROOT} && sbt clean
