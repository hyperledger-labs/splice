# -*- makefile -*-

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz
wallet-frontend := ${REPO_ROOT}/apps/wallet/frontend/build/index.html
directory-frontend := ${REPO_ROOT}/apps/wallet/directory/build/index.html
wallet-daml := ${REPO_ROOT}/apps/wallet/daml/.daml/dist/wallet-0.1.0.dar
directory-daml := ${REPO_ROOT}/apps/directory/daml/.daml/dist/directory-service-0.1.0.dar

$(app-bundle): $(wallet-frontend) $(directory-frontend)
	sbt bundle

$(wallet-daml):
	sbt protocGenerate damlBuild

$(directory-daml):
	sbt protocGenerate damlBuild

$(wallet-frontend): $(wallet-daml)
	cd ${REPO_ROOT}/apps/wallet/frontend && ./setup.sh
	cd ${REPO_ROOT}/apps/wallet/frontend && npm run build

$(directory-frontend): $(directory-daml)
	cd ${REPO_ROOT}/apps/directory/frontend && ./setup.sh
	cd ${REPO_ROOT}/apps/directory/frontend && npm run build

.PHONY: docker-build
docker-build: $(app-bundle)
	make -C cluster docker-build


.PHONY: clean
clean:
	make -C cluster clean
	rm -rf apps/app/target/release
