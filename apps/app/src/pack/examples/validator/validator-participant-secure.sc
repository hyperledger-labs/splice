val domainUrl = sys.env.get("DOMAIN_URL") match {
  case None => sys.error("Environment variable DOMAIN_URL was not set, set it to http://${targetcluster}.network.canton.global:5008")
  case Some(url) => url
}

println("Starting participant node")
validatorParticipant.start()

println(s"Connecting self-hosted validator to the domain $domainUrl")
validatorParticipant.domains.connect("global", domainUrl)

println("Secure validator participant bootstrap finished")
