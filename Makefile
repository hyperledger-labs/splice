# -*- makefile -*-

# svc-app depends on cn-app having been built first.
# For now, that dependency declaration is given by the order here.
apps := \
	cn-app \
	svc-app \
	canton-domain \
	canton-participant \
	docs \
	external-proxy

define make_apps
  for app in $(apps); do \
    if ! make -C $${app} $(1); then \
      exit 1; \
    fi \
  done
endef

.PHONY: docker-build
docker-build: test
	$(call make_apps, docker-build)

.PHONY: docker-push
docker-push: test
	$(call make_apps, docker-push)

.PHONY: docker-push-force
docker-push-force: test
	$(call make_apps, docker-push-force)

.PHONY: test
test:
	make -C cluster test

.PHONY: clean
clean:
	$(call make_apps, clean)
