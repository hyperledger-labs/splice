package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.google.protobuf.ByteString
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.{Get, Post}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{DsoRules, VoteRequest}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.PerClientIpRateLimitConfig
import org.lfdecentralizedtrust.splice.http.HttpRequestLimits
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  updateAutomationConfig,
  ConfigurableApp,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.config.CantonBftPeerConfig
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.automation.TopupMemberTrafficTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger

import java.util.Optional
import scala.concurrent.{blocking, Future}
import scala.util.{Success, Try}

// this test sets fees to zero, and that only works from 0.1.14 onwards
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_14
class ScanIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil {
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        (updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        ) andThen
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
              .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
          ))(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs_(config =>
          config.copy(
            synchronizerNodes = config.synchronizerNodes.copy(
              current = config.synchronizerNodes.current.copy(
                cantonBft = Some(CantonBftPeerConfig("http://testUrl:8081"))
              ),
              legacy = Some(
                config.synchronizerNodes.current.copy(
                  cantonBft = Some(CantonBftPeerConfig("http://legacyUrl:8082"))
                )
              ),
            ),
            parameters = config.parameters.copy(
              customTimeouts = config.parameters.customTimeouts.map {
                // guaranteeing a timeout for first test below
                case (key @ "getAcsSnapshot", _) =>
                  key -> NonNegativeFiniteDuration.ofMillis(1L)
                case other => other
              },
              // used for the rate limit test
              rateLimiting = config.parameters.rateLimiting.copy(
                rateLimiters =
                  config.parameters.rateLimiting.rateLimiters + ("listAnsEntries" -> PerClientIpRateLimitConfig(
                    ratePerSecond = 5
                  ))
              ),
            ),
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .withTrafficTopupsEnabled

  "getAcsSnapshot respects custom timeout" in { implicit env =>
    loggerFactory.assertLogsUnordered(
      Try(sv1ScanBackend.getAcsSnapshot(dsoParty, None)).isFailure should be(true),
      _.warningMessage should include("resulted in a timeout after 1 millisecond"),
      _.errorMessage should include(
        "Command failed, message: The server is taking too long to respond to the (GET) request"
      ),
    )
  }

  "return dso info same as the sv app" in { implicit env =>
    val scan = sv1ScanBackend.getDsoInfo()
    val svDsoInfo = sv1Backend.getDsoInfo()
    scan shouldBe svDsoInfo
    val dsoParty = scan.dsoParty
    clue("Returns physical synchronizer id") {
      sv1ScanBackend.getActivePhysicalSynchronizerSerial() shouldBe NonNegativeInt.zero
    }
    // sanity checks
    scan.dsoRules.contract.contractId.contractId should be(
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(DsoRules.COMPANION)(dsoParty)
        .loneElement
        .id
        .contractId
    )
    scan.amuletRules.contract.contractId.contractId should be(
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(AmuletRules.COMPANION)(dsoParty)
        .loneElement
        .id
        .contractId
    )
    scan.svNodeStates.values.map(
      _.contract.contractId.contractId
    ) should contain theSameElementsAs (
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(SvNodeState.COMPANION)(dsoParty)
        .map(_.id.contractId)
    )
  }

  "returns expected splice instance names" in { implicit env =>
    val spliceInstanceNames = sv1ScanBackend.getSpliceInstanceNames()

    spliceInstanceNames.networkName should be("Splice")
    spliceInstanceNames.networkFaviconUrl should be(
      "https://www.hyperledger.org/hubfs/hyperledgerfavicon.png"
    )
    spliceInstanceNames.amuletName should be("Amulet")
    spliceInstanceNames.amuletNameAcronym should be("AMT")
    spliceInstanceNames.nameServiceName should be("Amulet Name Service")
    spliceInstanceNames.nameServiceNameAcronym should be("ANS")
  }

  "getUpdateHistory should return 400 for invalid after timestamp" in { implicit env =>
    import env.{actorSystem, executionContext}
    registerHttpConnectionPoolsCleanup(env)

    val response = Http()
      .singleRequest(
        Post(
          s"${sv1ScanBackend.httpClientConfig.url}/api/scan/v0/updates"
        ).withEntity(
          HttpEntity(
            ContentTypes.`application/json`,
            s"""{"after":{"after_migration_id":1,"after_record_time":"Invalid"},"page_size":10}""",
          )
        )
      )
      .futureValue

    inside(response) {
      case _ if response.status == StatusCodes.BadRequest =>
        inside(Unmarshal(response.entity).to[String].value.value) {
          case Success(successfullResponse) =>
            successfullResponse should include(
              "Invalid timestamp: Text 'Invalid' could not be parsed at index 0"
            )
        }
    }
  }

  "reject request arrays longer than maxItems with 400" in { implicit env =>
    val maxItems = HttpRequestLimits.MaxRequestArrayItems
    val tooMany = maxItems + 1
    val at = CantonTimestamp.now()
    val migrationId = 0L
    val dso = dsoParty
    val parties = Vector.fill(tooMany)(dso)
    val openRound = sv1ScanBackend.getOpenAndIssuingMiningRounds()._1.headOption.value
    // faking an IssuingRound is easier than getting one, especially in this
    // suite, which pauses the advance trigger
    val issuingRound = ContractWithState(
      Contract(
        IssuingMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID,
        new IssuingMiningRound.ContractId(openRound.contractId.contractId),
        new IssuingMiningRound(
          dso.toProtoPrimitive,
          openRound.payload.round,
          SpliceUtil.damlDecimal(1.0),
          SpliceUtil.damlDecimal(1.0),
          SpliceUtil.damlDecimal(1.0),
          SpliceUtil.damlDecimal(1.0),
          openRound.payload.opensAt,
          openRound.payload.targetClosesAt,
          Optional.empty(),
        ),
        ByteString.EMPTY,
        openRound.contract.createdAt,
      ),
      ContractState.InFlight,
    )
    // contract ID type doesn't matter for this test
    val fakeVoteRequestCids = {
      val cid = new VoteRequest.ContractId(openRound.contractId.contractId)
      Seq.fill(tooMany)(cid)
    }

    val cases: Seq[(String, String, () => Any)] = Seq(
      (
        "/api/scan/v0/open-and-issuing-mining-rounds",
        "cached_open_mining_round_contract_ids",
        () =>
          sv1ScanBackend.getOpenAndIssuingMiningRounds(cachedOpenRounds =
            Seq.fill(tooMany)(openRound)
          ),
      ),
      (
        "/api/scan/v0/open-and-issuing-mining-rounds",
        "cached_issuing_round_contract_ids",
        () =>
          sv1ScanBackend.getOpenAndIssuingMiningRounds(cachedIssuingRounds =
            Seq.fill(tooMany)(issuingRound)
          ),
      ),
      (
        "/api/scan/v2/state/acs",
        "party_ids",
        () => sv1ScanBackend.getAcsSnapshotAtV2(at, migrationId, partyIds = Some(parties)),
      ),
      (
        "/api/scan/v2/state/acs",
        "templates",
        () =>
          sv1ScanBackend.getAcsSnapshotAtV2(
            at,
            migrationId,
            templates = Some {
              val pqn = PackageQualifiedName fromJavaCodegenCompanion AmuletRules.COMPANION
              Vector.fill(tooMany)(pqn)
            },
          ),
      ),
      (
        "/api/scan/v2/holdings/state",
        "owner_party_ids",
        () => sv1ScanBackend.getHoldingsStateAtV2(at, migrationId, parties),
      ),
      (
        "/api/scan/v0/holdings/summary",
        "owner_party_ids",
        () => sv1ScanBackend.getHoldingsSummaryAt(at, migrationId, parties),
      ),
      (
        "/api/scan/v1/holdings/summary",
        "owner_party_ids",
        () => sv1ScanBackend.getHoldingsSummaryAtV1(at, migrationId, parties),
      ),
      (
        "/api/scan/v0/voterequest",
        "vote_request_contract_ids",
        () => sv1ScanBackend.listVoteRequestsByTrackingCid(fakeVoteRequestCids),
      ),
      // technically an sv endpoint test, but an outlier and otherwise identical
      (
        "/api/sv/v0/admin/sv/voterequest",
        "vote_request_contract_ids",
        () => sv1Backend.listVoteRequestsByTrackingCid(fakeVoteRequestCids),
      ),
    )

    forEvery(Table(("path", "field", "call"), cases*)) { case (path, field, call) =>
      assertThrowsAndLogsCommandFailures(
        call(),
        _.errorMessage should (include(s"HTTP 400 Bad Request POST at '$path'") and include(
          s"Expected '$field' to contain at most $maxItems items, but contained $tooMany."
        )),
      )
    }
  }

  "return bft sequencers" in { implicit env =>
    val bftSequencers = sv1ScanBackend.listBftSequencers()
    bftSequencers should have size 2
    val expectedSequencerId =
      sv1Backend.appState.localSynchronizerNodes.current.sequencerAdminConnection.getSequencerId.futureValue
    forExactly(1, bftSequencers) { sequencer =>
      sequencer.url shouldBe "http://testurl:8081"
      sequencer.id shouldBe expectedSequencerId
    }
    forExactly(1, bftSequencers) { sequencer =>
      sequencer.url shouldBe "http://legacyurl:8082"
      sequencer.id shouldBe expectedSequencerId
    }
  }

  "respect rate limit" in { implicit env =>
    import env.{actorSystem, executionContext}

    def doCall() = {
      sv1ScanBackend.listEntries("", 1)
    }

    loggerFactory.assertLoggedWarningsAndErrorsSeq(
      {
        Try {
          doCall()
        }.discard

        Threading.sleep(1000) // wait for the rate limiter to start

        val results = SpliceRateLimiterTest
          .runRateLimited(
            10,
            50,
          ) {
            Future {
              blocking {
                doCall()
              }
            }
          } futureValue

        // 5 is the limit from where the rate limiter starts to kick in
        // then 5 every second
        // first second is 5 (full capacity) + 5 (capacity added after consumption)
        // then 5 every second
        // The 50 calls are emitted at 10/s, so ~5s of refill gives 30 in the ideal case. Allow one
        // more second of refill: throttle jitter or a slow first call stretches the window past 5s
        // and lets a further batch through (seen accepting 31). This is still far below the 50
        // attempted, so the assertion keeps proving that the limiter rejects.
        val maxAccepted = 35
        // account for bursts in the stream used to rate limit the calls in `runRateLimited`
        val minAccepted = 10
        results.count(identity) should (be >= minAccepted and be <= maxAccepted)

      },
      forAll(_) {
        _.message should include("Too Many Requests")
      },
    )
  }

  "accept invalid headers" in { implicit env =>
    import org.apache.pekko.http.scaladsl.model.headers as h
    import env.actorSystem
    registerHttpConnectionPoolsCleanup(env)

    // see pekko.http.server.parsing.ignore-illegal-header-for in application.conf
    // if pekko-http doesn't have a ModeledCompanion, it doesn't have a special parser,
    // so there is no need to suppress warnings for it
    val invalidHeaders = Seq[(h.ModeledCompanion[?], String)](
      (h.Authorization, "Bearer Bearer exxxxxxxxxx"),
      (h.Cookie, "foo=bar baz"),
      (h.Origin, "http://foo bar"),
      (h.`Proxy-Authorization`, "Basic dXNlcjpwYXNz,"), // "user:pass" with trailing comma
      (h.Referer, "http://foo bar"),
      (h.`User-Agent`, "OpenAPI-Generator/0.0.1/java"),
      (h.`X-Forwarded-For`, "1.2.3.4,"),
      (h.`X-Forwarded-Host`, "a b"),
      (h.`X-Real-Ip`, "1.2.3.4,"),
    ).map { case (companion, value) =>
      val header = RawHeader(companion.name, value)
      // using `User-Agent` (non-RawHeaders in general) fails the following check;
      // it cleans away the invalid part of the header,
      // so we have to use RawHeader to simulate the actual client case
      header.value shouldBe value
      import language.existentials
      companion.parseFromValueString(value) shouldBe a[
        Left[?, ?]
      ] withClue s"actual bad value for ${companion.name}"
      header
    }

    // SuppressingLogger does not catch the warning (from pekko-http)
    // if present, it's seen in checkErrors instead
    val response = Http()
      .singleRequest(
        Get(
          s"${sv1ScanBackend.httpClientConfig.url}/api/scan/v0/splice-instance-names"
        ).withHeaders(invalidHeaders)
      )
      .futureValue
    response.status shouldBe StatusCodes.OK
  }

  def triggerTopupAliceAndBob()(implicit env: SpliceTestConsoleEnvironment): (Boolean, Boolean) = {
    val aliceTopupTrigger =
      aliceValidatorBackend.appState.automation.trigger[TopupMemberTrafficTrigger]
    val bobTopupTrigger =
      bobValidatorBackend.appState.automation.trigger[TopupMemberTrafficTrigger]
    bobTopupTrigger.pause().futureValue
    aliceTopupTrigger.pause().futureValue
    (aliceTopupTrigger.runOnce().futureValue, bobTopupTrigger.runOnce().futureValue)
  }
}
