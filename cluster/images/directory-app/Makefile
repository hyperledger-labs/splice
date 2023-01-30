dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(dir)/bootstrap.sc $(dir)/target/directory-service-0.1.0.dar $(cn-image-build)
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell version-gen)

$(dir)/target/directory-service-0.1.0.dar: $(REPO_ROOT)/daml/directory-service/.daml/dist/directory-service-0.1.0.dar
	mkdir -p $(@D)
	cp $< $@

$(dir)/$(docker-push): $(cn-image-push)
