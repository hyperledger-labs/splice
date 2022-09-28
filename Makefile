# -*- makefile -*-

# svc-app depends on cn-app having been built first.
# For now, that dependency declaration is given by the order here.
apps := \
	cluster/images/cn-app \
	cluster/images/svc-app \
	cluster/images/scan-app \
	cluster/images/directory-app \
	cluster/images/canton-domain \
	cluster/images/canton-participant \
	cluster/images/docs \
	cluster/images/external-proxy \
	cluster/images/gcs-proxy \
	cluster/images/envoy-proxy

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
docker-push: docker-build
	$(call make_apps, docker-push)

.PHONY: docker-push-force
docker-push-force: docker-build
	$(call make_apps, docker-push-force)

.PHONY: docker-clean-build-push
docker-clean-build-push: test
	$(call make_apps, clean)
	$(call make_apps, docker-push)

.PHONY: docker-check
docker-check:
	$(call make_apps, docker-check)

.PHONY: test
test:
	make -C cluster test

.PHONY: clean
clean:
	$(call make_apps, clean)
