# -*- makefile -*-

.PHONY: docker-push
docker-push:
	make -C docs docker-push

.PHONY: clean
clean:
	make -C docs clean
