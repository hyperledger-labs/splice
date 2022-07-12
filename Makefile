# -*- makefile -*-

apps := canton-domain canton-participant docs

.PHONY: docker-build
docker-build: test
	for app in $(apps); do \
	  make -C $${app} docker-build; \
	done

.PHONY: docker-push
docker-push: test
	for app in $(apps); do \
	  make -C $${app} docker-push; \
	done

.PHONY: docker-push-force
docker-push-force: test
	for app in $(apps); do \
	  make -C $${app} docker-push-force; \
	done

.PHONY: test
test:
	make -C cluster test

.PHONY: clean
clean:
	for app in $(apps); do \
	  make -C $${app} clean; \
	done
