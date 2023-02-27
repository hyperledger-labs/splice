# -*- makefile -*-

$(dir)/$(docker-build): \
	$(dir)/target/entrypoint.sh \
	$(dir)/target/bootstrap-entrypoint.sc \
	$(dir)/target/tools.sc

$(dir)/target:
	mkdir -p $@

$(dir)/target/entrypoint.sh: $(dir)/../common/entrypoint.sh | $(dir)/target
	cp $< $@

$(dir)/target/bootstrap-entrypoint.sc: $(dir)/../common/bootstrap-entrypoint.sc | $(dir)/target
	cp $< $@

$(dir)/target/tools.sc: $(dir)/../common/tools.sc | $(dir)/target
	cp $< $@
