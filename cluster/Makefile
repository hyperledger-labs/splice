# -*- makefile -*-

test-vars=\
	--tla-str gcpRegion=region \
    --tla-str gcpRepoName=project/repo \
    --tla-str imageTag=tag \
    --tla-str ipAddr=ipaddr

.PHONY: format
format:
	jsonnetfmt -i *.jsonnet

.PHONY: test
test:
	diff test-results/expected.json	<(jsonnet $(test-vars) canton-network-config.jsonnet)

.PHONY: test-update
test-update:
	jsonnet $(test-vars) \
		canton-network-config.jsonnet > test-results/expected.json
