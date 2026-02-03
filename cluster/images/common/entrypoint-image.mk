# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0



$(dir)/$(docker-build): \
	$(dir)/target/entrypoint.sh \
	$(dir)/target/bootstrap-entrypoint.sc \
	$(dir)/target/tools.sh \
	$(dir)/target/monitoring.conf \
	$(dir)/target/storage.conf \
	$(dir)/target/parameters.conf

$(dir)/target/entrypoint.sh: $(dir)/../common/entrypoint.sh | $(dir)/target
	cp $< $@

$(dir)/target/monitoring.conf: $(dir)/../common/monitoring.conf | $(dir)/target
	cp $< $@

$(dir)/target/storage.conf: $(dir)/../common/storage.conf | $(dir)/target
	cp $< $@

$(dir)/target/parameters.conf: $(dir)/../common/parameters.conf | $(dir)/target
	cp $< $@

$(dir)/target/bootstrap-entrypoint.sc: $(dir)/../common/bootstrap-entrypoint.sc | $(dir)/target
	cp $< $@

$(dir)/target/tools.sh: $(dir)/../common/tools.sh | $(dir)/target
	cp $< $@
