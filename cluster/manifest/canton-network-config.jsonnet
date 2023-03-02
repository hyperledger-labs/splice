local networkDefaults = import "./network-defaults.jsonnet";

local c = import "./cluster.jsonnet";

local ingress = import "./ingress-config.jsonnet";

local docs = import "./docs-config.jsonnet";
local splitwell = import "./splitwell-config.jsonnet";
local svc = import "./svc-config.jsonnet";
local validator1 = import "./validator1-config.jsonnet";

local cantonNetwork(config) =
  local appDeployments =
    docs.deployments(config) +
    svc.deployments(config) +
    validator1.deployments(config) +
    splitwell.deployments(config);

  c.cluster(config, appDeployments + ingress.deployments(config, appDeployments));

function(
  gcpRegion,
  gcpRepoName,
  gcpDnsProject,
  gcpDnsSASecret,
  imageTag,
  ipAddr,
  clusterName,
  clusterDnsName,
  fixedTokens,
  tickDuration,
) cantonNetwork(networkDefaults {
  gcpRegion: gcpRegion,
  gcpRepoName: gcpRepoName,
  gcpDnsProject: gcpDnsProject,
  gcpDnsSASecret: gcpDnsSASecret,
  imageTag: imageTag,
  ipAddr: ipAddr,
  clusterName: clusterName,
  clusterDnsName: clusterDnsName,
  fixedTokens: fixedTokens,
  tickDuration: tickDuration,
})
