# -*- makefile -*-

test-vars=\
	--tla-str gcpRegion=region \
    --tla-str gcpRepoName=project/repo \
    --tla-str gcpDnsProject=dns-project \
	--tla-str gcpDnsSvcAcct=dns-service-account \
    --tla-str imageTag=tag \
    --tla-str ipAddr=ipaddr \
    --tla-str clusterName=cluster \
	--tla-str clusterDnsName=cluster.network.global

.PHONY: format
format:
	jsonnetfmt -i *.jsonnet

.PHONY: test
test:
	jsonnet $(test-vars) canton-network-config.jsonnet > test-results/actual.json
	diff test-results/expected.json	test-results/actual.json
	rm test-results/actual.json

.PHONY: test-update
test-update:
	jsonnet $(test-vars) \
		canton-network-config.jsonnet > test-results/expected.json
