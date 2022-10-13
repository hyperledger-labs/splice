println("Bootstrapping validator1 validator...")

println("Waiting for validator1 to finish init...")
validator1_validator_backend.waitForInitialization(2.minutes)

println("Onboarding users")

validator1_validator_backend.onboardUser("alice")
validator1_validator_backend.onboardUser("bob")
validator1_validator_backend.onboardUser("charlie")

// TODO (M1-92): This will later be replaced by the app manager
println("Uploading directory DAR")
validator1_validator_backend.remoteParticipant.dars.upload("directory-service-0.1.0.dar")

println("Bootstrapped validator1 validator.")
