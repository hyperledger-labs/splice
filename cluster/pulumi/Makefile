dir := $(call current_dir)

$(dir)/install: $(dir)/package.json
	cd $(@D) && npm install && touch install

.PHONY: $(dir)/clean
$(dir)/clean:
	cd $(@D) && rm -rfv node_modules install


.PHONY: $(dir)/format
$(dir)/format: $(dir)/install
	cd $(@D) && npm run format:fix

