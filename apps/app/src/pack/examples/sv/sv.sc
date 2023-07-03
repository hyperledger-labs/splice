println("Starting SV validator and SV app")
sv.start()
svValidator.start()

logger.info("Waiting for SV app to finish init...")
sv.waitForInitialization()

logger.info("Waiting for SV validator app to finish init...")
svValidator.waitForInitialization()

println("SV app and validator app started succesfully!")
println(sv.getSvcInfo())
