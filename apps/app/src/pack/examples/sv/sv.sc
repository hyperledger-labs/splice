println("Starting SV app")
sv.start()

logger.info("Waiting for SV app to finish init...")
sv.waitForInitialization(2.minutes)

println("SV app started succesfully!")
println(sv.getDebugInfo())
