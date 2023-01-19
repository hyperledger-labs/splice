println("Checking cluster participant access via ping...")

val clusterPingDuration = clusterParticipant1.health.ping(clusterParticipant1, timeout = 10.second)

println("...cluster participant ping complete, duration: " + clusterPingDuration)

val clusterAddr = sys.env.get("CLUSTER_ADDR").get

println("Connecting local participant to domain: " + clusterAddr)

localParticipant1.domains.connect("global", s"http://$clusterAddr:5008")

println("....waiting for active domain connection.")

utils.retry_until_true(localParticipant1.domains.active("svc_domain"))

println("Checking local to remote access via ping test...")

var localPingDuration = localParticipant1.health.ping(clusterParticipant1, timeout = 30.second)

println("....local to remote ping test complete, duration: " + localPingDuration)

System.exit(0)
