# -*- makefile -*-
include ../../../build-tools/common.mk

src := Dockerfile empty.conf

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz
wallet-frontend := ${REPO_ROOT}/apps/wallet/frontend/build/index.html
directory-frontend := ${REPO_ROOT}/apps/wallet/directory/build/index.html
target-bundle := target/coin-0.1.0-SNAPSHOT.tar.gz
wallet-daml := ${REPO_ROOT}/apps/wallet/daml/.daml/dist/wallet-0.1.0.dar
directory-daml := ${REPO_ROOT}/apps/directory/daml/.daml/dist/directory-service-0.1.0.dar

$(docker-build): $(src) $(target-bundle)

$(target-bundle): $(app-bundle)
	mkdir -p $(@D)
	cp $< $@

$(app-bundle): $(wallet-frontend)
	cd ${REPO_ROOT} && sbt bundle

$(wallet-daml):
	cd ${REPO_ROOT} && sbt protocGenerate damlBuild

$(directory-daml):
	cd ${REPO_ROOT} && sbt protocGenerate damlBuild

$(wallet-frontend): $(wallet-daml)
	cd ${REPO_ROOT}/apps/wallet/frontend && ./setup.sh
	cd ${REPO_ROOT}/apps/wallet/frontend && npm run build

$(directory-frontend): $(directory-daml)
	cd ${REPO_ROOT}/apps/directory/frontend && ./setup.sh
	cd ${REPO_ROOT}/apps/directory/frontend && npm run build

.PHONY: clean-sbt
clean-sbt:
	cd ${REPO_ROOT} && sbt clean
