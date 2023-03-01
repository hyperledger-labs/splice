// The memory limit settings below control the pod memory request and limits.
//
// jvmHeapMemoryFactor controls the fraction of a pod's memory
// allocation that goes to the JVM heap. If the heap factor is 0.75, and
// limit is 4096, then the Xmx and Xms settings for the JVM will be 3072.
// This, then, leaves 1024MB for other purposes, including the operating
// system, disk buffers, and other off-heap memory allocated by the JVM.
//
// Hitting the Xms/Xmx memory limit in the JVM will be manifested as an
// OOM by the JVM itself.  If the Pod is in an OOMKilled state, this
// signals that an OS process running on the pod (maybe the JVM, maybe
// something else) was unable to allocate OS memory past the Pod limit.

{
  domainCpu: 1,
  domainMemoryMib: 4096,
  participantCpu: 1,
  participantMemoryMib: 32768,
  postgresCpu: 2,
  postgresMemoryMib: 8192,
  ledgerDatabaseGib: 20,
  numberOfSvNodes: 4,
  jvmHeapMemoryFactor: 0.75,
  externalIPRanges: [
    "35.194.81.56/32",
    "35.198.147.95/32",
    "35.189.40.124/32",
    "34.132.91.75/32",
    "18.210.210.130/32",
  ],
  tls: {
    issuerName: "letsencrypt-production",
    issuerServer: "https://acme-v02.api.letsencrypt.org/directory",
  },
}
