// appended to bootstrap-canton.sc when start-canton -g set

bootstrapOtherDomain("globalUpgrade", globalUpgradeSequencer, globalUpgradeMediator)

println("Connecting sv1 participant to global upgrade domain...")
sv1Participant.domains.connect_local(
  globalUpgradeSequencer,
  alias = Some(DomainAlias.tryCreate("global-upgrade")),
)
