dir := $(call current_dir)

target-load-tester := $(dir)/target/test
load-tester := ${REPO_ROOT}/load-tester/dist

$(dir)/$(docker-build): $(target-load-tester)

$(target-load-tester): $(load-tester)
	mkdir -p $(@D)
	cp -r $< $@
