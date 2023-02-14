// memoryLimitMiB values for deployments are taken emperically from
// DevNet with `kubectl top pod`. Note that these were taken on a very
// lightly loaded cluster and will very likely need to be revised for
// clusters with higher loads.

{
  domainCpu: 1,
  domainMemoryMib: 2048,
  participantCpu: 1,
  participantMemoryMib: 8192,
  postgresCpu: 2,
  postgresMemoryMib: 4096,
  ledgerDatabaseGib: 20,
  numberOfSvNodes: 4,
  externalIPRanges: [
    "35.194.81.56/32",
    "35.198.147.95/32",
    "35.189.40.124/32",
    "34.132.91.75/32",
    "18.210.210.130/32",
  ],
}
