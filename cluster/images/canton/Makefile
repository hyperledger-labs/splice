# -*- makefile -*-
include ../common.mk

target-canton := target/canton
target-classpath := target/canton-classpath
auth-service := ${REPO_ROOT}/canton/community/participant/target/scala-2.13/classes/com/digitalasset/canton/participant/ledger/api/CantonAdminTokenAuthService.class

src := Dockerfile empty.conf target/entrypoint.sh $(target-canton) $(target-classpath)

target/entrypoint.sh: ../common/entrypoint.sh
	cp $< $@

$(docker-build): $(src)

$(target-canton):
	rm -rf $@
	mkdir -p $@
	cp -R $$(dirname $$(dirname $$(which canton)))/. $@
	chmod -R +w $@
	sed -i -E 's|#!.*/bin/sh|#!/usr/bin/env sh|' $@/bin/canton

$(target-classpath): $(auth-service)
	rm -rf $@
	mkdir -p $@/com/digitalasset/canton/participant/ledger/api
	cp $< \
	  $@/com/digitalasset/canton/participant/ledger/api

