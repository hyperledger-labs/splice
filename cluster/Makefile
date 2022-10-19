# With the exception of docs and the -proxy images, all of these
# images depend on cn-apps having been built first. This dependency
# order is only expressed in the order of this list and must be
# maintained for the build to operate properly.
apps := \
	images/cn-app \
	images/svc-app \
	images/scan-app \
	images/directory-app \
	images/canton-domain \
	images/canton-participant \
	images/docs \
	images/external-proxy \
	images/gcs-proxy \
	images/envoy-proxy \
	images/validator1-participant \
	images/validator1-validator-app \
	images/validator1-wallet-app \
	images/validator1-wallet-web-ui \
	images/validator1-directory-web-ui \
	images/validator1-splitwise-web-ui \
	images/splitwise-participant \
	images/splitwise-validator-app \
	images/splitwise-app

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
	make -C manifest test

.PHONY: clean
clean:
	$(call make_apps, clean)
