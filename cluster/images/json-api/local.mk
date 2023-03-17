dir := $(call current_dir)
target-json-api := $(dir)/target/json-api

$(dir)/$(docker-build): $(dir)/json-api-app.conf $(target-json-api)

$(target-json-api):
	rm -rf $@
	mkdir -p $@
	cp -R $$(dirname $$(dirname $$(which json-api)))/. $@
	chmod -R +w $@
