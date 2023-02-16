dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(dir)/target/splitwell-0.1.0.dar $(dir)/pre-bootstrap.sh
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell version-gen)

$(dir)/target/splitwell-0.1.0.dar: $(REPO_ROOT)/daml/splitwell/.daml/dist/splitwell-0.1.0.dar
	mkdir -p $(@D)
	cp $< $@

include cluster/images/cn-base-image-dep.mk
