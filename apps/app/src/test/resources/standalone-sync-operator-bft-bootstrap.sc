import com.digitalasset.canton.admin.api.client.data.{
  SubmissionRequestAmplification,
  TrafficControlParameters,
}
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong}

// Bootstraps the four-node synchronizer in standalone-sync-operator-bft.conf with the thresholds
// Splice sets for the global synchronizer: three of the four sequencers sign topology changes, and
// two sequencers or two mediators must agree.
val bftSequencers = sequencers.local.sortBy(_.name)
val bftMediators = mediators.local.sortBy(_.name)
val owners = bftSequencers.take(3)

val synchronizerId = bootstrap.synchronizer(
  synchronizerName = "syncOperatorBft",
  sequencers = bftSequencers,
  // Each mediator connects only to its own node's sequencer, as SV mediators do.
  mediatorsToSequencers = bftMediators
    .zip(bftSequencers)
    .map { case (mediator, sequencer) =>
      mediator -> (Seq(sequencer), PositiveInt.one, NonNegativeInt.zero)
    }
    .toMap,
  synchronizerOwners = bftSequencers,
  synchronizerThreshold = PositiveInt.tryCreate(3),
  staticSynchronizerParameters = StaticSynchronizerParameters.defaultsWithoutKMS(ProtocolVersion.v36),
  mediatorRequestAmplification = SubmissionRequestAmplification.NoAmplification,
  mediatorThreshold = PositiveInt.tryCreate(2),
).logical

// Raise the sequencer threshold, which genesis leaves at 1.
owners.foreach(owner =>
  owner.topology.sequencers.propose(
    synchronizerId,
    threshold = PositiveInt.tryCreate(2),
    active = bftSequencers.map(_.id),
    signedBy = Some(owner.id.uid.namespace.fingerprint),
    synchronize = None,
  )
)
utils.retry_until_true {
  bftSequencers.head.topology.sequencers
    .list(store = Some(TopologyStoreId.Synchronizer(synchronizerId)))
    .exists(_.item.threshold == PositiveInt.tryCreate(2))
}

// Traffic control with a zero base rate, as on a dedicated synchronizer, which the sync operators
// require. Canton's defaults otherwise, as bootstrap-canton.sc sets for splitwell.
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
    synchronize = None,
  )
)
