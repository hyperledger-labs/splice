dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(dir)/target/splitwise-0.1.0.dar $(cn-image)
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell version-gen)

$(dir)/target/splitwise-0.1.0.dar: $(REPO_ROOT)/daml/splitwise/.daml/dist/splitwise-0.1.0.dar
	mkdir -p $(@D)
	cp $< $@
