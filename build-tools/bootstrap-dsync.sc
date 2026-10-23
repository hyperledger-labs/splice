val sequencers = Seq(sequencer1, sequencer2, sequencer3, sequencer4)
val mediators = Seq(mediator1, mediator2, mediator3, mediator4)
val owners = sequencers.take(3)

println("Bootstrapping synchronizer...")
val synchronizerId = bootstrap
  .synchronizer(
    synchronizerName = "dedicated",
    sequencers = sequencers,
    mediatorsToSequencers = mediators.map {
      _ -> (Seq(ownsequencer), PositiveInt.one, NonNegativeInt.zero)
    }.toMap,
    synchronizerOwners = sequencers,
    synchronizerThreshold = PositiveInt.tryCreate(3),
    staticSynchronizerParameters =
      StaticSynchronizerParameters.defaultsWithoutKMS(ProtocolVersion.latest),
    mediatorRequestAmplification = SubmissionRequestAmplification.NoAmplification,
    mediatorThreshold = PositiveInt.tryCreate(2),
  )
  .logical

println("Setting sequencer threshold to 2...")
owners.foreach(owner =>
  owner.topology.sequencers.propose(
    synchronizerId,
    threshold = PositiveInt.tryCreate(2),
    active = sequencers.map(_.id),
    signedBy = Some(owner.id.uid.namespace.fingerprint),
    synchronize = None,
  )
)

utils.retry_until_true {
  sequencers.head.topology.sequencers
    .list(store = Some(TopologyStoreId.Synchronizer(synchronizerId)))
    .exists(_.item.threshold == PositiveInt.tryCreate(2))
}

println("Restricting onboarding...")
owners.foreach(owner =>
  owner.topology.synchronizer_parameters.propose_update(
    synchronizerId,
    _.update(onboardingRestriction = OnboardingRestriction.RestrictedOpen),
    signedBy = Some(owner.id.uid.namespace.fingerprint),
  )
)

utils.retry_until_true {
  sequencers.head.topology.synchronizer_parameters
    .get_dynamic_synchronizer_parameters(synchronizerId)
    .onboardingRestriction == OnboardingRestriction.RestrictedOpen
}

println("Turning on traffic management...")
owners.foreach(owner =>
  owner.topology.synchronizer_parameters.propose_update(
    synchronizerId,
    _.update(trafficControl =
      Some(
        TrafficControlParameters(
          maxBaseTrafficAmount = NonNegativeLong.zero,
          readVsWriteScalingFactor = PositiveInt.tryCreate(200),
          maxBaseTrafficAccumulationDuration = PositiveFiniteDuration.ofMinutes(10),
          setBalanceRequestSubmissionWindowSize = PositiveFiniteDuration.ofMinutes(5),
          enforceRateLimiting = true,
          baseEventCost = NonNegativeLong.zero,
          freeConfirmationResponses = false,
        )
      )
    ),
    signedBy = Some(owner.id.uid.namespace.fingerprint),
  )
)

utils.retry_until_true {
  sequencers.head.topology.synchronizer_parameters
    .get_dynamic_synchronizer_parameters(synchronizerId)
    .trafficControl
    .isDefined
}
