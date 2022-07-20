# -*- makefile -*-

.PHONY: format
format:
	jsonnetfmt -i *.jsonnet

.PHONY: test
test:
	diff test-results/expected.json	<(jsonnet --tla-str imageTag=tag --tla-str ipAddr=ipaddr canton-network-config.jsonnet)

.PHONY: test-update
test-update:
	jsonnet --tla-str imageTag=tag \
	        --tla-str ipAddr=ipaddr \
		canton-network-config.jsonnet > test-results/expected.json
