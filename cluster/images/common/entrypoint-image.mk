# -*- makefile -*-

$(dir)/$(docker-build): \
	$(dir)/target/entrypoint.sh \
	$(dir)/target/bootstrap-entrypoint.sc \
	$(dir)/target/tools.sc

$(dir)/target/entrypoint.sh: $(dir)/../common/entrypoint.sh
	cp $< $@

$(dir)/target/bootstrap-entrypoint.sc: $(dir)/../common/bootstrap-entrypoint.sc
	cp $< $@

$(dir)/target/tools.sc: $(dir)/../common/tools.sc
	cp $< $@

