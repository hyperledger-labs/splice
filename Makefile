# -*- makefile -*-

.PHONY: docker-build
docker-build:
	make -C docs docker-build

.PHONY: docker-push
docker-push:
	make -C docs docker-push

.PHONY: clean
clean:
	make -C docs clean
