println("Bootstrapping validator1 validator...")

println("Waiting for validator1 to finish init...")
validator1_validator_backend.waitForInitialization(2.minutes)

// TODO (tech-debt): This will later be replaced by the app manager
println("Uploading DARs")
validator1_validator_backend.remoteParticipant.dars.upload("directory-service-0.1.0.dar")
validator1_validator_backend.remoteParticipant.dars.upload("splitwise-0.1.0.dar")

println("Bootstrapped validator1 validator.")
