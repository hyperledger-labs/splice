dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(dir)/target/directory-service-0.1.0.dar
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell get-snapshot-version)

$(dir)/target/directory-service-0.1.0.dar: $(REPO_ROOT)/daml/directory-service/.daml/dist/directory-service-0.1.0.dar
	mkdir -p $(@D)
	cp $< $@

include cluster/images/cn-base-image-dep.mk
