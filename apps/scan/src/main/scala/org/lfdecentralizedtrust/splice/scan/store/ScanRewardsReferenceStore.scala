// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.rewards.AssetActivityParams
import org.lfdecentralizedtrust.splice.scan.store.db.ScanRewardsReferenceTables.ScanRewardsReferenceStoreRowData
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData
import org.lfdecentralizedtrust.splice.util.{Contract, TemplateJsonDecoder}

import scala.concurrent.{ExecutionContext, Future}

/** This is a temporal contract store (TcsStore) to provide efficient asOf round
  * lookups of FeaturedAppRight and OpenMiningRound contracts
  * necessary for rewards calculations. It is a separate store with its own
  * tables to enable it to have its own indexing scheme and pruning schedule to
  * ensure consistent performance.
  */
trait ScanRewardsReferenceStore extends AppStore {

  def key: ScanRewardsReferenceStore.Key

  override def dsoPartyId = key.dsoParty

  /** Waits for this store to be initialized.
    * All other methods on this store will independently wait for initialization
    * to complete before returning results, this method is useful for cases where
    * the caller wants to wait for initialization to complete before starting
    * to use this store.
    */
  def waitUntilInitialized: Future[Unit]

  /** For a batch of record times, resolve the oldest open mining round at each time.
    * Returns map from record_time to the round's info.
    * This will wait till the round info could be obtained for record_times
    * which are yet to be ingested.
    *
    * On the other hand if round info could not be obtained for a particular record_time
    * then the Map will not contain the entry for that.
    * This could happen in two scenarios
    * 1. If the record_time or the round's openAt is before the ingestion start.
    * 2. When the ingestion start could not be determined
    *    This will happen if no contracts ingestion has happened in the archived table,
    *    ie the store ingestion has just begun and no OpenMiningRound archival has been observed.
    */
  def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit
      tc: TraceContext
  ): Future[Map[CantonTimestamp, ScanRewardsReferenceStore.ActiveRoundInfo]]

  /** Returns the providers and opsParties of the FeaturedAppRights active as of the given time.
    *
    * If a provider has multiple FeaturedAppRights, their opsParties are combined,
    * the highest activityWeight is used, and the highest appKind is used
    * (FAK_General < FAK_AssetMoving < FAK_Asset), to be in favor of the app.
    *
    * If an ops party belongs to FeaturedAppRights of multiple providers,
    * then the lexicographically lowest provider is chosen.
    */
  def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[ScanRewardsReferenceStore.FeaturedAppParties]

  /** Returns the set of SV participant UIDs from the DsoRules active as of the given time.
    * Returns an empty set only if asOf time is before the creation time of oldest DsoRules ingested.
    */
  def lookupSvParticipantIdsAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]]

  /** Returns the AmuletConfig active at the given time
    * Returns None only if asOf time is before the creation time of oldest AmuletRules ingested.
    */
  def lookupAmuletConfigAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[AmuletConfig[USD]]]

  /** Look up an OpenMiningRound contract by its round number.
    * Checks both the active ACS table and the archive table,
    * since the round may have already been closed by the time the trigger runs.
    */
  def lookupOpenMiningRoundByNumber(
      roundNumber: Long
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[OpenMiningRound.ContractId, OpenMiningRound]]]

  /** The highest OpenMiningRound round number archived at or before asOf.
    * Returns None without waiting when asOf is before the earliest observed
    * archival, otherwise waits until the store's ingestion has reached asOf.
    */
  def lookupLatestArchivedOpenMiningRound(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[Long]]

  /** List active CalculateRewardsV2 contracts, sorted by round number ascending.
    */
  def listActiveCalculateRewardsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]]

  def lookupArchivedAtForOpenMiningRound(
      roundNumber: Long
  )(implicit tc: TraceContext): Future[Option[CantonTimestamp]]

  /** The lowest round number that may be safe to prune
    * The criterion is based on the presence of `OpenMiningRound`,
    *  `CalculateRewardsV2`, or `ProcessRewardsV2` for a round in the active table.
    * If all of these contracts for a round are archived then the reward processing is done.
    * In addition we also check that none of these three contracts are active for any lower round.
    */
  def lookupLowestPrunableArchivedRewardRound()(implicit tc: TraceContext): Future[Option[Long]]

  /** The lowest round number that is safe to prune given that verdicts have been
    * ingested up to `lastIngestedRecordTime`, and that data is retained for at
    * least `retentionPeriod` before it may be pruned.
    */
  def lookupPrunableRewardRound(
      lastIngestedRecordTime: CantonTimestamp,
      now: CantonTimestamp,
      retentionPeriod: NonNegativeFiniteDuration,
  )(implicit tc: TraceContext): Future[Option[Long]] =
    lookupLowestPrunableArchivedRewardRound().flatMap {
      case None => Future.successful(None)
      case Some(roundNumber) =>
        // The earliest archived_at row acts as an indicator of the "ingestion
        // start" of this store, and therefore we need to ensure that we keep at
        // least one archived row with a record_time lower than the `opensAt` of
        // the oldest round that is still open as of lastIngestedRecordTime.
        //
        // A simple way to ensure this is to compare that `opensAt` with the
        // archived_at of round roundNumber + 1.
        for {
          activeRoundO <- lookupActiveOpenMiningRounds(Seq(lastIngestedRecordTime))
            .map(_.get(lastIngestedRecordTime))
          archivedAtNextO <- lookupArchivedAtForOpenMiningRound(roundNumber + 1)
        } yield (activeRoundO, archivedAtNextO) match {
          case (
                Some(
                  ScanRewardsReferenceStore.ActiveRoundInfo(
                    activeRoundNumber,
                    activeRoundOpensAt,
                    _,
                  )
                ),
                Some(archivedAtNext),
              ) =>
            val retainUntil = now.minus(retentionPeriod.asJava)
            if (retainUntil <= archivedAtNext) {
              // We compare retentionPeriod with archived_at of roundNumber + 1
              // just to avoid an additional DB lookup of archived_at of roundNumber
              logger.debug(
                s"Skipping pruning of round $roundNumber as round ${roundNumber + 1}'s archivedAt " +
                  s"($archivedAtNext) is still within the retention period."
              )
              None
            } else if (activeRoundOpensAt <= archivedAtNext) {
              logger.debug(
                s"Skipping pruning of round $roundNumber as the ingestion's currently active round " +
                  s"$activeRoundNumber opened at $activeRoundOpensAt, which is not yet past round " +
                  s"${roundNumber + 1}'s archivedAt ($archivedAtNext)."
              )
              None
            } else Some(roundNumber)
          case (None, _) =>
            // This happens after bootstrapping this store while the round
            // advancement is lagging, as then the oldest open round as of
            // lastIngestedRecordTime opened before the store's ingestion start.
            logger.debug(
              s"Skipping pruning of round $roundNumber as no active OpenMiningRound could be " +
                s"resolved as of the last ingested verdict record time ($lastIngestedRecordTime)."
            )
            None
          case (_, None) =>
            // We can't have lookupActiveOpenMiningRounds resolve an activeRoundO
            // while the roundNumber is the lowest prunable round in store, and the roundNumber + 1 has not archived
            // as the roundNumber + 1 must have opened before roundNumber - 1 was archived.
            logger.warn(
              s"This should never happen. Skipping pruning of round $roundNumber as round ${roundNumber + 1} has not archived yet " +
                s"even though an active OpenMiningRound resolved as of $lastIngestedRecordTime."
            )
            None
        }
    }

  /** Deletes all rows from the archive table with `archived_at <=` the record_time at
    * which round's `OpenMiningRound` contract was archived, and thereby moves
    * the ingestion start of this store to the earliest remaining archival.
    * Fails if there is no such archival, or if the round's archival has not been observed.
    *
    * Here we don't have a lower bound on the `archived_at`, so this API should
    * be used with care, preferably ensuring that the deletion happens one round at a time.
    *
    * Returns number of rows deleted.
    */
  def pruneArchivedUpToRound(
      roundNumber: Long
  )(implicit tc: TraceContext): Future[Long]

  /** List active CalculateRewardsV2 contracts for the given round.
    */
  def listActiveCalculateRewardsV2ForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]]

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[
    ScanRewardsReferenceStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] =
    ScanRewardsReferenceStore.contractFilter(key)
}

object ScanRewardsReferenceStore {

  def apply(
      key: ScanRewardsReferenceStore.Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      migrationId: Long,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
      defaultLimit: Limit,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): ScanRewardsReferenceStore = {
    val dbStore = new db.DbScanRewardsReferenceStore(
      key = key,
      storage = storage,
      loggerFactory = loggerFactory,
      retryProvider = retryProvider,
      migrationId = migrationId,
      participantId = participantId,
      ingestionConfig = ingestionConfig,
      defaultLimit = defaultLimit,
    )
    new CachingScanRewardsReferenceStore(dbStore, loggerFactory)
  }

  /** The info we need from OpenMiningRound for activity-record calcs
    *
    * @param assetActivityParams the params from the round's reward config,
    *                            None if they are not all set.
    */
  final case class ActiveRoundInfo(
      roundNumber: Long,
      opensAt: CantonTimestamp,
      assetActivityParams: Option[AssetActivityParams],
  )

  /** The featured apps active as of a given time, see `lookupFeaturedAppPartiesAsOf`.
    *
    * @param providers map from the provider of a FeaturedAppRight to its (appKind, activityWeight)
    * @param opsPartyProviders map from an ops party to its provider
    */
  final case class FeaturedAppParties(
      providers: Map[String, (splice.amulet.FeaturedAppKind, BigDecimal)],
      opsPartyProviders: Map[String, String],
  )

  case class Key(
      dsoParty: PartyId,
      synchronizerId: SynchronizerId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("dsoParty", _.dsoParty),
      param("synchronizerId", _.synchronizerId),
    )
  }

  def contractFilter(
      key: ScanRewardsReferenceStore.Key
  ): MultiDomainAcsStore.ContractFilter[
    ScanRewardsReferenceStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter
    val dso = key.dsoParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter[
      ScanRewardsReferenceStoreRowData,
      AcsInterfaceViewRowData.NoInterfacesIngested,
    ](
      key.dsoParty,
      templateFilters = Map(
        mkFilter(splice.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanRewardsReferenceStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(splice.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanRewardsReferenceStoreRowData(
              contract = contract,
              featuredAppRightProvider =
                Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
        },
        mkFilter(splice.dsorules.DsoRules.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanRewardsReferenceStoreRowData(contract = contract)
        },
        mkFilter(splice.amuletrules.AmuletRules.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanRewardsReferenceStoreRowData(contract = contract)
        },
        mkFilter(splice.amulet.rewardaccountingv2.CalculateRewardsV2.COMPANION)(
          co => co.payload.dso == dso,
          versionGuard = { case (pkgVersionSupport, now) =>
            (tc) => pkgVersionSupport.supportsTrafficBasedAppRewards(Seq(key.dsoParty), now)(tc)
          },
        ) { contract =>
          ScanRewardsReferenceStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(splice.amulet.rewardaccountingv2.ProcessRewardsV2.COMPANION)(
          co => co.payload.dso == dso,
          versionGuard = { case (pkgVersionSupport, now) =>
            (tc) => pkgVersionSupport.supportsTrafficBasedAppRewards(Seq(key.dsoParty), now)(tc)
          },
        ) { contract =>
          ScanRewardsReferenceStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
      ),
      interfaceFilters = Map.empty,
      synchronizerFilter = Some(key.synchronizerId),
    )
  }
}
