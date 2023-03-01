local networkDefaults = import "./network-defaults.jsonnet";

local c = import "./cluster.jsonnet";

local ingress = import "./ingress-config.jsonnet";
local splitwell = import "./splitwell-config.jsonnet";
local svc = import "./svc-config.jsonnet";
local validator1 = import "./validator1-config.jsonnet";

local cantonNetwork(config) =
  local appDeployments =
    [
      c.deployment(config, "docs", [
        {
          name: "http",
          port: 80,
        },
        {
          name: "https",
          port: 443,
        },
      ]),
      c.deployment(config, "gcs-proxy", [
        {
          name: "http",
          port: 8080,
          internalOnly: true,
        },
      ], cpuRequest=0.5, memoryLimitMiB=512),
    ] +
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
