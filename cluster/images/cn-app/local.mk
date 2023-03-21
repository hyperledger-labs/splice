dir := $(call current_dir)

target-bundle := $(dir)/target/cn-node-0.1.0-SNAPSHOT.tar.gz
app-bundle := ${REPO_ROOT}/apps/app/target/release/cn-node-0.1.0-SNAPSHOT.tar.gz

include ${REPO_ROOT}/cluster/images/common/entrypoint-image.mk

$(dir)/$(docker-build): $(dir)/empty.conf $(dir)/target/entrypoint.sh $(target-bundle)

$(target-bundle): $(app-bundle)
	mkdir -p $(@D)
	cp $< $@
