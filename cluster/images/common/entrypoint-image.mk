# -*- makefile -*-

$(dir)/$(docker-build): \
	$(dir)/target/entrypoint.sh \
	$(dir)/target/bootstrap-entrypoint.sc \
	$(dir)/target/tools.sh \
	$(dir)/target/metrics.conf

$(dir)/target:
	mkdir -p $@

$(dir)/target/entrypoint.sh: $(dir)/../common/entrypoint.sh | $(dir)/target
	cp $< $@

$(dir)/target/metrics.conf: $(dir)/../common/metrics.conf | $(dir)/target
	cp $< $@

$(dir)/target/bootstrap-entrypoint.sc: $(dir)/../common/bootstrap-entrypoint.sc | $(dir)/target
	cp $< $@

$(dir)/target/tools.sh: $(dir)/../common/tools.sh | $(dir)/target
	cp $< $@
