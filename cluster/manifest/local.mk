dir := $(call current_dir)

test-vars=\
	--tla-str gcpRegion=region \
    --tla-str gcpRepoName=project/repo \
    --tla-str gcpDnsProject=dns-project \
	--tla-str gcpDnsSASecret=dns-service-account \
    --tla-str imageTag=tag \
    --tla-str ipAddr=ipaddr \
    --tla-str clusterName=cluster \
	--tla-str clusterDnsName=cluster.network.global \
	--tla-code fixedTokens=false \
	--tla-code tickDuration=null

.PHONY: $(dir)/test
$(dir)/test:
	jsonnet $(test-vars) $(@D)/canton-network-config.jsonnet > $(@D)/test-results/actual.json
	diff $(@D)/test-results/expected.json	$(@D)/test-results/actual.json
	rm $(@D)/test-results/actual.json

.PHONY: $(dir)/test-update
$(dir)/test-update:
	jsonnet $(test-vars) \
		$(@D)/canton-network-config.jsonnet > $(@D)/test-results/expected.json
