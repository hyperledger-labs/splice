// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  PackageVersionSupport,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.http.HttpRequestLimits
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.scan.metrics.ScanHttpApiMetrics
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  AppActivityStore,
  ScanEventStore,
  ScanStore,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.store.{AppStoreWithIngestion, UpdateHistory}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class HttpScanHandlerValidatorFaucetsTest extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val actorSystem: ActorSystem = ActorSystem("HttpScanHandlerValidatorFaucetsTest")
  implicit val tc: TraceContext = TraceContext.empty

  private def handler(scanStore: ScanStore): HttpScanHandler = {
    val storeWithIngestion = mock[AppStoreWithIngestion[ScanStore]]
    when(storeWithIngestion.store).thenReturn(scanStore)

    new HttpScanHandler(
      svParty = PartyId.tryFromProtoPrimitive("sv::dummy"),
      svUserName = "sv-user",
      spliceInstanceNames = SpliceInstanceNamesConfig(
        networkName = "network",
        networkFaviconUrl = "https://example.invalid/favicon",
        amuletName = "amulet",
        amuletNameAcronym = "A",
        nameServiceName = "name-service",
        nameServiceNameAcronym = "NS",
      ),
      participantAdminConnection = mock[ParticipantAdminConnection],
      synchronizerNodeService = mock[SynchronizerNodeService[ScanSynchronizerNode]],
      storeWithIngestion = storeWithIngestion,
      updateHistory = mock[UpdateHistory],
      appRewardsStore = mock[DbScanAppRewardsStore],
      appActivityStore = mock[AppActivityStore],
      snapshotStore = mock[AcsSnapshotStore],
      eventStore = mock[ScanEventStore],
      bulkStorage = None,
      scanApiMetrics = mock[ScanHttpApiMetrics],
      dsoAnsResolver = mock[DsoAnsResolver],
      miningRoundsCacheTimeToLiveOverride = None,
      enableForcedAcsSnapshots = false,
      clock = mock[Clock],
      loggerFactory = NamedLoggerFactory.root,
      packageVersionSupport = mock[PackageVersionSupport],
      bftSequencers = Seq.empty,
      initialRound = "0",
      externalTransactionHashThresholdTime = None,
      updateHistoryMaxPageSize = 100,
      publicUrlO = None,
      lsuRollForwardConfigO = None,
      perAcsSnapshotTablesEnabled = true,
    )
  }

  private def getValidatorFaucets(
      scanStore: ScanStore,
      validatorIds: Vector[String],
  ): Future[ScanResource.GetValidatorFaucetsByValidatorResponse] =
    Future.delegate(
      handler(scanStore).getValidatorFaucetsByValidator(
        ScanResource.GetValidatorFaucetsByValidatorResponse
      )(validatorIds)(tc)
    )

  "getValidatorFaucetsByValidator" should {

    "accept up to maxItems validator_ids" in {
      val scanStore = mock[ScanStore]
      when(scanStore.getValidatorLicenseByValidator(any[Vector[PartyId]])(any[TraceContext]))
        .thenReturn(Future.successful(Seq.empty))
      val validatorIds = Vector.fill(HttpRequestLimits.MaxRequestArrayItems)("validator::dummy")

      getValidatorFaucets(scanStore, validatorIds).futureValue shouldBe a[
        ScanResource.GetValidatorFaucetsByValidatorResponseOK
      ]
    }

    "reject more than maxItems validator_ids without querying the store" in {
      val scanStore = mock[ScanStore]
      val maxItems = HttpRequestLimits.MaxRequestArrayItems
      val validatorIds = Vector.fill(maxItems + 1)("validator::dummy")

      val ex = getValidatorFaucets(scanStore, validatorIds).failed.futureValue
      ex shouldBe a[StatusRuntimeException]
      val status = ex.asInstanceOf[StatusRuntimeException].getStatus
      status.getCode shouldBe Status.Code.INVALID_ARGUMENT
      status.getDescription shouldBe
        s"Expected 'validator_ids' to contain at most $maxItems items, but contained ${maxItems + 1}."
      verify(scanStore, never).getValidatorLicenseByValidator(any[Vector[PartyId]])(
        any[TraceContext]
      )
    }
  }
}
