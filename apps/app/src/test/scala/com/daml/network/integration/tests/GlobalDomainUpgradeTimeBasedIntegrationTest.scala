package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.{Template, Identifier as JIdentifier}
import com.daml.ledger.javaapi.data.codegen.{
  ContractId,
  Update,
  Contract as CodegenContract,
  ContractCompanion as TemplateCompanion,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.{
  cns,
  svlocal,
  svcrules as svcr,
  svonboarding as so,
  validatoronboarding as vo,
  wallet as cnw,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.config.CNNodeConfigTransforms.{
  updateAllAutomationConfigs,
  updateAllValidatorConfigs,
}
import com.daml.network.store.MultiDomainAcsStore.ContractState.Assigned
import com.daml.network.sv.util.SvUtil.dummySvRewardWeight
import com.daml.network.util.{
  AssignedContract,
  CoinConfigSchedule,
  ConfigScheduleUtil,
  Contract,
  ContractWithState,
}
import com.daml.network.validator.config.AppManagerConfig
import com.digitalasset.canton.{DiscardOps, DomainAlias}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.store.TopologyStoreId
import org.scalatest.prop.TableDrivenPropertyChecks.forEvery as tForEvery

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** You must `start-canton` with `-g` to run this test locally. */
class GlobalDomainUpgradeTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment
    with ConfigScheduleUtil {

  override def environmentDefinition =
    super.environmentDefinition
      .addConfigTransforms(
        (_, config) =>
          updateAllAutomationConfigs(c =>
            c.copy(
              // Need to disable triggers so workflows stay open
              enableSvcGovernance = false,
              enableClosedRoundArchival = false,
              enableSvRewards = false,
            )
          )(config),
        (_, config) =>
          updateAllValidatorConfigs { case (name, c) =>
            // Enable app manager so migration kicks in.
            // We don't actually use app maager functionality so the URLs are stubs.
            if (name == "sv1Validator") {
              c.copy(
                appManager = Some(
                  AppManagerConfig(
                    issuerUrl = "https://example.com",
                    appManagerUiUrl = "https://example.com",
                    appManagerApiUrl = "https://example.com",
                    jsonApiUrl = "https://example.com",
                    audience = "https://example.com",
                    initialRegisteredApps = Map.empty,
                    initialInstalledApps = Map.empty,
                  )
                )
              )
            } else {
              c
            }
          }(config),
      )

  private[this] val globalUpgradeDomain = DomainAlias.tryCreate("global-upgrade")

  private[this] val mostDistantPossibleExpiry = com.daml.lf.data.Time.Timestamp.MaxValue.toInstant

  "scheduled global domain upgrade happens" in { implicit env =>
    initSvcWithSv1Only() withClue "spin up Svc"
    val sv1WalletUser = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)

    val timeUntilNewRule = defaultTickDuration
    val timeToWaitForNewRule = tickDurationWithBuffer

    // If you fail here, see class scaladoc.
    val globalUpgradeId = sv1Backend.participantClient.domains.id_of(
      globalUpgradeDomain
    ) withClue "find the global-upgrade domain ID"

    val sv1Party = sv1Backend.getSvcInfo().svParty

    // host the svc party as it's stored in the domain stores only
    sv1Backend.appState.participantAdminConnection
      .ensureInitialPartyToParticipant(
        TopologyStoreId.DomainStore(globalUpgradeId),
        svcParty,
        sv1Backend.participantClientWithAdminToken.id,
        sv1Party.uid.namespace.fingerprint,
      )
      .futureValue
      .discard

    val (previousGlobalId, coinRulesCid) = clue("change coinconfig to migrate domains") {
      inside(sv1ScanBackend.getCoinRules()) {
        case ContractWithState(firstCoinRules, Assigned(global1)) =>
          val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
          val currentSchedule = firstCoinRules.payload.configSchedule
          val activeDomainId =
            CoinConfigSchedule(currentSchedule).getConfigAsOf(now).globalDomain.activeDomain

          globalUpgradeId.toProtoPrimitive should not be activeDomainId
          global1.toProtoPrimitive shouldBe activeDomainId

          val upgradeAfterTick = new Tuple2(
            env.environment.clock.now.add(timeUntilNewRule.asJava).toInstant,
            mkUpdatedCoinConfig(
              currentSchedule,
              defaultTickDuration,
              nextDomainId = Some(globalUpgradeId),
            ),
          )

          val setScheduleResult = cleanAndAddNewSchedule(
            AssignedContract(firstCoinRules, global1),
            upgradeAfterTick,
          ) withClue "set config schedule with upgraded domain"

          eventually() {
            inside(sv1ScanBackend.getCoinRules()) { case ContractWithState(secondCoinRules, _) =>
              secondCoinRules.contractId should not be firstCoinRules.contractId
              setScheduleResult shouldBe secondCoinRules.contractId
            }
          }
          (global1, setScheduleResult)
      }
    }

    clue("trigger CoinRules cache invalidation right after config change") {
      eventually() {
        try sv1WalletClient.tap(1)
        catch {
          case e: com.digitalasset.canton.console.CommandFailure => fail(e)
        }
      }
    }

    val svcRulesCid = sv1Backend.getSvcInfo().svcRules.contractId

    def nonEmptyOnSv1[
        TCid <: ContractId[T],
        T <: Template,
    ](companion: Contract.Companion.Template[TCid, T]) =
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(companion)(svcParty)
        .nonEmpty

    def exerciseSvc[T](update: Update[T]) =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(svcParty, sv1Party),
          readAs = Seq.empty,
          update = update,
          domainId = Some(previousGlobalId),
        )

    def createSampleAndEnsurePresence[
        TCid <: ContractId[T],
        T <: Template,
    ](companion: Contract.Companion.Template[TCid, T])(payload: T): TCid = {
      val (created, _) = actAndCheck(
        s"create sample ${companion.TEMPLATE_ID.getEntityName}",
        exerciseSvc(payload.create()),
      )(s"ensure ${companion.TEMPLATE_ID.getEntityName} is there", _ => nonEmptyOnSv1(companion))
      companion.toContractId(new ContractId(created.contractId.contractId))
    }

    val dummyRound = new cc.types.Round(42)
    val dummyDecimal = new java.math.BigDecimal(42)

    clue("create governance contracts of various kinds") {
      actAndCheck(
        "create VoteRequest",
        sv1Backend.createVoteRequest(
          sv1Party.toProtoPrimitive,
          new svcr.actionrequiringconfirmation.ARC_SvcRules(
            new svcr.svcrules_actionrequiringconfirmation.SRARC_AddMember(
              new svcr.SvcRules_AddMember(
                "alice",
                "Alice",
                dummySvRewardWeight,
                "alice-participant-id",
                dummyRound,
                previousGlobalId.toProtoPrimitive,
              )
            )
          ),
          "url",
          "description",
          sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
        ),
      )(
        "VoteRequest and Vote should be there",
        _ =>
          inside(sv1Backend.listVoteRequests()) { case Seq(onlyReq) =>
            sv1Backend.listVotes(Vector(onlyReq.contractId.contractId)) should have size 1
          },
      )

      actAndCheck(
        "create sample ElectionRequest",
        exerciseSvc(
          svcRulesCid.exerciseSvcRules_RequestElection(
            sv1Party.toProtoPrimitive,
            new svcr.electionrequestreason.ERR_OtherReason("watch the request get migrated"),
            Seq(sv1Party.toProtoPrimitive).asJava,
          )
        ),
      )("ElectionRequest should be there", _ => nonEmptyOnSv1(svcr.ElectionRequest.COMPANION))

      actAndCheck(
        "create sample Confirmation",
        exerciseSvc(
          svcRulesCid.exerciseSvcRules_ConfirmAction(
            sv1Party.toProtoPrimitive,
            new svcr.actionrequiringconfirmation.ARC_SvcRules(
              new svcr.svcrules_actionrequiringconfirmation.SRARC_RemoveMember(
                new svcr.SvcRules_RemoveMember("nonsense")
              )
            ),
          )
        ),
      )("ensure Confirmation is there", _ => nonEmptyOnSv1(svcr.Confirmation.COMPANION))

      actAndCheck(
        "create sample SvOnboardingRequest",
        exerciseSvc(
          svcRulesCid.exerciseSvcRules_StartSvOnboarding(
            "irrelevant name",
            sv1Party.toProtoPrimitive, // irrelevant party
            "PAR::sv::1220f3e2",
            "irrelevant token",
            sv1Party.toProtoPrimitive,
          )
        ),
      )("ensure SvOnboardingRequest is there", _ => nonEmptyOnSv1(so.SvOnboardingRequest.COMPANION))

      createSampleAndEnsurePresence(svlocal.approvedsvidentity.ApprovedSvIdentity.COMPANION)(
        new svlocal.approvedsvidentity.ApprovedSvIdentity(
          sv1Party.toProtoPrimitive,
          "irrelevant name",
          "irrelevant key",
        )
      )

      createSampleAndEnsurePresence(vo.UsedSecret.COMPANION)(
        new vo.UsedSecret(
          sv1Party.toProtoPrimitive,
          "irrelevant secret",
          sv1ValidatorBackend.getValidatorPartyId().toProtoPrimitive,
        )
      )

      createSampleAndEnsurePresence(svcr.SvReward.COMPANION)(
        new svcr.SvReward(
          svcParty.toProtoPrimitive,
          sv1Party.toProtoPrimitive,
          dummyRound,
          new java.math.BigDecimal("42"),
        )
      )

      createSampleAndEnsurePresence(so.SvOnboardingConfirmed.COMPANION)(
        new so.SvOnboardingConfirmed(
          sv1Party.toProtoPrimitive,
          "irrelevant name",
          "PAR::sv::1220f3e2",
          dummySvRewardWeight,
          "observing domain migration",
          svcParty.toProtoPrimitive,
          mostDistantPossibleExpiry,
        )
      )
    }

    clue("create SVC-signed coin contracts of various kinds") {
      val (oldestRound, newestRound) = {
        val rounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._1
        (rounds.headOption.value, rounds.lastOption.value)
      }

      createSampleAndEnsurePresence(cc.round.IssuingMiningRound.COMPANION)(
        new cc.round.IssuingMiningRound(
          svcParty.toProtoPrimitive,
          dummyRound,
          dummyDecimal,
          dummyDecimal,
          dummyDecimal,
          dummyDecimal,
          oldestRound.payload.opensAt,
          newestRound.payload.targetClosesAt,
          None.toJava,
        )
      )

      createSampleAndEnsurePresence(cc.round.ClosedMiningRound.COMPANION)(
        new cc.round.ClosedMiningRound(
          svcParty.toProtoPrimitive,
          dummyRound,
          dummyDecimal,
          dummyDecimal,
          dummyDecimal,
          dummyDecimal,
          None.toJava,
        )
      )

      actAndCheck(
        "create sample FeaturedAppRight",
        exerciseSvc(coinRulesCid.exerciseCoinRules_DevNet_FeatureApp(sv1Party.toProtoPrimitive)),
      )("ensure FeaturedAppRight is there", _ => nonEmptyOnSv1(cc.coin.FeaturedAppRight.COMPANION))

      createSampleAndEnsurePresence(cc.coin.UnclaimedReward.COMPANION)(
        new cc.coin.UnclaimedReward(svcParty.toProtoPrimitive, dummyDecimal)
      )

      createSampleAndEnsurePresence(cc.coinimport.ImportCrate.COMPANION) {
        val svc = svcParty.toProtoPrimitive
        val receiver = svc
        new cc.coinimport.ImportCrate(
          svc,
          receiver,
          new cc.coinimport.importpayload.IP_Coin(
            new cc.coin.Coin(
              svc,
              receiver,
              new cc.fees.ExpiringAmount(
                dummyDecimal,
                dummyRound,
                new cc.fees.RatePerRound(dummyDecimal),
              ),
              java.util.Optional.empty,
            )
          ),
        )
      }
    }

    val dummyDistantRelTime = new RelTime(1000000000000L)

    // TODO (#8386) revert 8315's da0c91c29abf for directory stubs

    clue("create app-manager contracts of various kinds") {
      import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
      import com.daml.network.http.v0.definitions as httpdefs
      import io.circe.syntax.*
      val validator = sv1ValidatorBackend.getValidatorPartyId()
      val provider = validator
      val version = "0"

      createSampleAndEnsurePresence(appManagerCodegen.AppRelease.COMPANION)(
        new appManagerCodegen.AppRelease(
          validator.toProtoPrimitive,
          provider.toProtoPrimitive,
          version,
          httpdefs.AppRelease(version, Vector.empty).asJson.noSpaces,
        )
      )

      createSampleAndEnsurePresence(appManagerCodegen.AppConfiguration.COMPANION)(
        new appManagerCodegen.AppConfiguration(
          validator.toProtoPrimitive,
          provider.toProtoPrimitive,
          0,
          httpdefs
            .AppConfiguration(0, "foo", "urn:example.com", Vector.empty, Vector.empty)
            .asJson
            .noSpaces,
        )
      )

      createSampleAndEnsurePresence(appManagerCodegen.RegisteredApp.COMPANION)(
        new appManagerCodegen.RegisteredApp(
          validator.toProtoPrimitive,
          provider.toProtoPrimitive,
        )
      )

      createSampleAndEnsurePresence(appManagerCodegen.InstalledApp.COMPANION)(
        new appManagerCodegen.InstalledApp(
          validator.toProtoPrimitive,
          provider.toProtoPrimitive,
          "https://example.com/apps/registered/provider::00000",
        )
      )

      createSampleAndEnsurePresence(appManagerCodegen.ApprovedReleaseConfiguration.COMPANION)(
        new appManagerCodegen.ApprovedReleaseConfiguration(
          validator.toProtoPrimitive,
          provider.toProtoPrimitive,
          0,
          httpdefs.ReleaseConfiguration(Vector.empty, version, httpdefs.Timespan()).asJson.noSpaces,
          "00000000",
        )
      )
    }

    def protectAppRewardCoupons = sv1Backend.leaderBasedAutomation
      .trigger[com.daml.network.sv.automation.leaderbased.ExpireRewardCouponsTrigger]

    val subscriptionRequestCid = clue("create user wallet contracts of various kinds") {
      val svc = svcParty.toProtoPrimitive
      val validator = sv1ValidatorBackend.getValidatorPartyId()
      val provider = sv1WalletUser
      val maxTimestamp = com.daml.lf.data.Time.Timestamp.MaxValue.toInstant

      protectAppRewardCoupons.pause().futureValue

      createSampleAndEnsurePresence(cc.coin.AppRewardCoupon.COMPANION)(
        new cc.coin.AppRewardCoupon(
          svcParty.toProtoPrimitive,
          provider.toProtoPrimitive,
          false,
          dummyDecimal,
          dummyRound,
        )
      )

      val lockedCoinCid = createSampleAndEnsurePresence(cc.coin.LockedCoin.COMPANION)(
        new cc.coin.LockedCoin(
          new cc.coin.Coin(
            svcParty.toProtoPrimitive,
            provider.toProtoPrimitive,
            new cc.fees.ExpiringAmount(
              dummyDecimal,
              dummyRound,
              new cc.fees.RatePerRound(dummyDecimal),
            ),
            java.util.Optional.empty,
          ),
          new cc.expiry.TimeLock(
            java.util.List.of(),
            maxTimestamp,
          ),
        )
      )

      createSampleAndEnsurePresence(cc.coin.ValidatorRewardCoupon.COMPANION)(
        new cc.coin.ValidatorRewardCoupon(
          svcParty.toProtoPrimitive,
          validator.toProtoPrimitive,
          dummyDecimal,
          dummyRound,
        )
      )

      createSampleAndEnsurePresence(cc.validatorlicense.ValidatorFaucetCoupon.COMPANION)(
        new cc.validatorlicense.ValidatorFaucetCoupon(
          svcParty.toProtoPrimitive,
          validator.toProtoPrimitive,
          dummyRound,
        )
      )

      val appPaymentRequestCid =
        createSampleAndEnsurePresence(cnw.payment.AppPaymentRequest.COMPANION)(
          new cnw.payment.AppPaymentRequest(
            sv1WalletUser.toProtoPrimitive,
            java.util.List.of(),
            provider.toProtoPrimitive,
            svc,
            maxTimestamp,
            "irrelevant description",
          )
        )

      createSampleAndEnsurePresence(cnw.payment.AcceptedAppPayment.COMPANION)(
        new cnw.payment.AcceptedAppPayment(
          sv1WalletUser.toProtoPrimitive,
          java.util.List.of,
          provider.toProtoPrimitive,
          svc,
          lockedCoinCid,
          dummyRound,
          appPaymentRequestCid,
        )
      )

      val dummyPaymentAmount = new cnw.payment.PaymentAmount(dummyDecimal, cnw.payment.Currency.CC)

      val dummySubscriptionData = new cnw.subscriptions.SubscriptionData(
        sv1WalletUser.toProtoPrimitive,
        svc,
        provider.toProtoPrimitive,
        svc,
        "irrelevant description",
      )

      val subscriptionRequestCid =
        createSampleAndEnsurePresence(cnw.subscriptions.SubscriptionRequest.COMPANION)(
          new cnw.subscriptions.SubscriptionRequest(
            dummySubscriptionData,
            new cnw.subscriptions.SubscriptionPayData(
              dummyPaymentAmount,
              dummyDistantRelTime,
              dummyDistantRelTime,
            ),
          )
        )

      createSampleAndEnsurePresence(cnw.subscriptions.Subscription.COMPANION)(
        new cnw.subscriptions.Subscription(dummySubscriptionData, subscriptionRequestCid)
      )

      createSampleAndEnsurePresence(cnw.transferoffer.TransferOffer.COMPANION)(
        new cnw.transferoffer.TransferOffer(
          svc,
          sv1WalletUser.toProtoPrimitive,
          svc,
          dummyPaymentAmount,
          "irrelevant description",
          maxTimestamp,
          "irrelevant tracking id",
        )
      )

      createSampleAndEnsurePresence(cnw.transferoffer.AcceptedTransferOffer.COMPANION)(
        new cnw.transferoffer.AcceptedTransferOffer(
          svc,
          sv1WalletUser.toProtoPrimitive,
          svc,
          dummyPaymentAmount,
          maxTimestamp,
          "irrelevant tracking id",
        )
      )

      subscriptionRequestCid
    }

    clue("create SVC-signed CNS contracts of various kinds") {
      import com.daml.network.util.CNNodeUtil.defaultCnsConfig

      val svc = svcParty.toProtoPrimitive

      createSampleAndEnsurePresence(cns.CnsRules.COMPANION)(
        new cns.CnsRules(svc, defaultCnsConfig())
      )

      createSampleAndEnsurePresence(cns.CnsEntry.COMPANION)(
        new cns.CnsEntry(
          svc,
          svc,
          "irrelevant name",
          "urn:example.com",
          "irrelevant description",
          mostDistantPossibleExpiry,
        )
      )

      createSampleAndEnsurePresence(cns.CnsEntryContext.COMPANION)(
        new cns.CnsEntryContext(
          svc,
          svc,
          "irrelevant name",
          "urn:example.com",
          "irrelevant description",
          subscriptionRequestCid,
        )
      )
    }

    advanceTime(timeToWaitForNewRule) withClue "advance time"

    clue("see whether the svcrules moves") {
      eventually() {
        val cid: String = svcRulesCid.contractId
        sv1ValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .lookup_contract_domain(svcParty, Set(cid)) shouldBe Map(
          cid -> globalUpgradeId
        )
      }
    }

    clue("see whether coinrules follows svcrules") {
      eventually() {
        sv1ScanBackend.getCoinRules().state shouldBe Assigned(globalUpgradeId)
      }
    }

    import language.existentials
    type FilterableCompanion =
      TemplateCompanion[_ <: CodegenContract[TCid, Data], TCid, Data] forSome {
        type Data <: Template
        type TCid <: ContractId[Data]
      }

    def c(fc: FilterableCompanion, queryParty: PartyId = svcParty) =
      (fc, queryParty)

    def allContractsMigrated(rows: (FilterableCompanion, PartyId)*) = {
      val companions = Table[JIdentifier, FilterableCompanion, PartyId](
        ("template", "companion", "querying party"),
        rows.map { case (c, p) => (c.TEMPLATE_ID, c, p) }: _*
      )
      eventually() {
        tForEvery(companions) { (_, companion, queryingParty) =>
          val contractIds = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(companion)(queryingParty)
            .map(_.id.contractId)
          contractIds should not be empty
          val domains =
            sv1ValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .lookup_contract_domain(queryingParty, contractIds.toSet)

          tForEvery(Table("contract ID", contractIds: _*)) { cid =>
            domains.get(cid) shouldBe Some(globalUpgradeId)
          }
        }
      }
    }

    clue("see whether governance contracts follow svcrules") {
      import com.daml.network.sv.store.SvSvStore.templatesMovedByMyAutomation as templatesMovedBySvAutomation
      allContractsMigrated(
        c(svcr.Vote.COMPANION),
        c(svcr.VoteRequest.COMPANION),
        c(svcr.Confirmation.COMPANION),
        c(svcr.SvReward.COMPANION),
        c(svcr.ElectionRequest.COMPANION),
        c(so.SvOnboardingRequest.COMPANION),
        c(so.SvOnboardingConfirmed.COMPANION),
      )
      // only have the sv party as a stakeholder
      allContractsMigrated(templatesMovedBySvAutomation.map(c(_, sv1Party)): _*)
    }

    // wait a tick for next, as below wait for CoinRules to move
    advanceTime(tickDurationWithBuffer)

    clue(
      "see whether coin contracts signed only by SVC (in particular mining rounds) follow svcrules"
    ) {
      import com.daml.network.sv.store.SvSvcStore
      allContractsMigrated(
        SvSvcStore.coinRulesFollowers
          filterNot Set(
            cc.coin.SvcReward.COMPANION, // TODO (#7210)
            cnw.subscriptions.TerminatedSubscription.COMPANION, // TODO (#8386)
          )
          map (c(_)): _*
      )
    }

    clue(
      "wait for scan to yield transferred CoinRules. "
        + "This does not mean the cache was invalidated, though"
    ) {
      eventually() {
        sv1ScanBackend.getCoinRules().state shouldBe Assigned(globalUpgradeId)
      }
    }

    // TODO (#8135) tap here instead to check improved cache invalidation

    clue("see whether coin/wallet contracts signed by validator follow coinrules") {
      val sv1ValidatorParty = sv1ValidatorBackend.getValidatorPartyId()
      import com.daml.network.validator.store.ValidatorStore.templatesMovedByMyAutomation as templatesMovedByValidatorAutomation
      allContractsMigrated(
        templatesMovedByValidatorAutomation(true) map (c(_, sv1ValidatorParty)): _*
      )
    }

    clue("see whether coin/wallet contracts signed by wallet user follow coinrules") {
      import com.daml.network.wallet.store.UserWalletStore.templatesMovedByMyAutomation as templatesMovedByUserWalletAutomation
      allContractsMigrated(
        templatesMovedByUserWalletAutomation
          filterNot Set( // TODO (#8386) remove filtering
            cnw.subscriptions.SubscriptionInitialPayment.COMPANION,
            cnw.subscriptions.SubscriptionIdleState.COMPANION,
            cnw.subscriptions.SubscriptionPayment.COMPANION,
          )
          map (c(_, sv1WalletUser)): _*
      )
    }

    protectAppRewardCoupons.resume()
  }

  private[this] def cleanAndAddNewSchedule(
      start: AssignedContract[cc.coinrules.CoinRules.ContractId, cc.coinrules.CoinRules],
      newSchedule: Tuple2[Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD]],
  )(implicit fp: FixtureParam): cc.coinrules.CoinRules.ContractId = {
    sv1ScanBackend
      .getCoinRules()
      .payload
      .configSchedule
      .futureValues
      .forEach(config => {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            update = start.contractId
              .exerciseCoinRules_RemoveFutureCoinConfigSchedule(config._1),
            domainId = Some(start.domain),
          )
          .exerciseResult
      })
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(svcParty),
        readAs = Seq.empty,
        update = start.contractId.exerciseCoinRules_AddFutureCoinConfigSchedule(newSchedule),
        domainId = Some(start.domain),
      )
      .exerciseResult
  }
}
