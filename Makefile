# -*- makefile -*-

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz

.PHONY: build
build: $(app-bundle)

$(app-bundle):
	sbt bundle

.PHONY: docker-build
docker-build: $(app-bundle)
	make -C cluster docker-build


.PHONY: clean
clean:
	make -C cluster clean
	rm -rf apps/app/target/release
