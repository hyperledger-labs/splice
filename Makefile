# -*- makefile -*-

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz
wallet-frontend := ${REPO_ROOT}/apps/wallet/frontend/build/index.html
directory-frontend := ${REPO_ROOT}/apps/wallet/directory/build/index.html
splitwise-frontend := ${REPO_ROOT}/apps/wallet/splitwise/build/index.html
wallet-daml := ${REPO_ROOT}/apps/wallet/daml/.daml/dist/wallet-0.1.0.dar
directory-daml := ${REPO_ROOT}/apps/directory/daml/.daml/dist/directory-service-0.1.0.dar
splitwise-daml := ${REPO_ROOT}/apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar

$(app-bundle): $(wallet-frontend) $(directory-frontend) $(splitwise-frontend)
	sbt bundle

$(wallet-daml):
	sbt protocGenerate damlBuild

$(directory-daml):
	sbt protocGenerate damlBuild

$(wallet-frontend): $(wallet-daml)
	cd ${REPO_ROOT}/apps && ${REPO_ROOT}/build-tools/npm-install.sh
	cd ${REPO_ROOT}/apps && npm run build -w common-protobuf
	cd ${REPO_ROOT}/apps && npm run build -w common-frontend

	cd ${REPO_ROOT}/apps && npm run build -w wallet-frontend

$(directory-frontend): $(directory-daml)
	cd ${REPO_ROOT}/apps && ${REPO_ROOT}/build-tools/npm-install.sh
	cd ${REPO_ROOT}/apps && npm run build -w common-protobuf
	cd ${REPO_ROOT}/apps && npm run build -w common-frontend

	cd ${REPO_ROOT}/apps && npm run build -w directory-frontend

$(splitwise-frontend): $(splitwise-daml)
	cd ${REPO_ROOT}/apps && ${REPO_ROOT}/build-tools/npm-install.sh
	cd ${REPO_ROOT}/apps && npm run build -w common-protobuf
	cd ${REPO_ROOT}/apps && npm run build -w common-frontend

	cd ${REPO_ROOT}/apps && npm run build -w splitwise-frontend

.PHONY: docker-build
docker-build: $(app-bundle)
	make -C cluster docker-build


.PHONY: clean
clean:
	make -C cluster clean
	rm -rf apps/app/target/release
