# -*- makefile -*-

.PHONY: docker-build
docker-build:
	make -C canton-domain docker-build
	make -C canton-particpiant docker-build
	make -C docs docker-build

.PHONY: docker-push
docker-push:
	make -C canton-domain docker-push
	make -C canton-participant docker-push
	make -C docs docker-push

.PHONY: test
test:
	make -C cluster test

.PHONY: clean
clean:
	make -C canton-domain clean
	make -C canton-participant clean
	make -C docs clean
