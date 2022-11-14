# -*- makefile -*-

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz

.PHONY: build
build: $(app-bundle)	## Build the Canton Coin app bundle

$(app-bundle):
	sbt bundle

.PHONY: docker-build
docker-build: $(app-bundle)
	make -C cluster docker-build


.PHONY: clean
clean:	## Clean most build state (use clean-all if this is not sufficient)
	make -C cluster clean
	rm -rf apps/app/target/release

.PHONY: clean-all
clean-all: clean	## Completely clean all local build state, including model codegen.
	sbt clean-cn

.PHONY: format
format:	## Automatically reformat and apply scalaFix to source code
	sbt formatFix

.PHONY: help
help:	## Show list of available make targets
	@cat Makefile | grep -e "^[a-zA-Z_\-]*: *.*## *" | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'
