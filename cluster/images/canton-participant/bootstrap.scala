println("Bootstrapping CN participant...")

val domainLabel = "svc_domain"
val domainUrl = System.getProperty("DOMAIN_URL", "http://canton-domain:5008")

if (`svc_participant`.domains.list_connected().isEmpty) {
    println("No registered domains, so connecting to the SVC domain for the first time...")

    `svc_participant`.domains.connect(domainLabel, domainUrl)
    utils.retry_until_true(`svc_participant`.domains.active(domainLabel))

    println("Executing self ping for connection verification...")
    `svc_participant`.health.ping(`svc_participant`)
}

println("Bootstrapped CN participant!")
