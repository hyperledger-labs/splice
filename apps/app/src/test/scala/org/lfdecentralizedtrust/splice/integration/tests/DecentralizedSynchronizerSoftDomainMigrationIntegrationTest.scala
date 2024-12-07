package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.{Template, Identifier as JIdentifier}
import com.daml.ledger.javaapi.data.codegen.{
  ContractId,
  Update,
  Contract as CodegenContract,
  ContractCompanion as TemplateCompanion,
}
import org.lfdecentralizedtrust.splice.automation.{AssignTrigger, TransferFollowTrigger}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  ans,
  dsorules,
  svonboarding as so,
  validatoronboarding as vo,
  wallet as spw,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAllAutomationConfigs,
  updateAllValidatorConfigs,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState.Assigned
import org.lfdecentralizedtrust.splice.automation.AmuletConfigReassignmentTrigger
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.ElectionRequestTrigger
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.PruneAmuletConfigScheduleTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.{
  ReceiveSvRewardCouponTrigger,
  SubmitSvStatusReportTrigger,
}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AssignedContract,
  ConfigScheduleUtil,
  Contract,
  ContractWithState,
  UpdateHistoryTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.config.AppManagerConfig
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.store.TopologyStoreId
import org.scalatest.prop.TableDrivenPropertyChecks.forEvery as tForEvery

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*
import scala.jdk.OptionConverters.*

/** You must `start-canton` with `-g` to run this test locally. */
class DecentralizedSynchronizerSoftDomainMigrationIntegrationTest
    extends SvIntegrationTestBase
    with ConfigScheduleUtil
    with WalletTestUtil
    with UpdateHistoryTestUtil {

  // Fails with unexpected CreatedEvent roots
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition =
    super.environmentDefinition
      // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the dso to consider unclaimed
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .addConfigTransforms(
        (_, config) =>
          (updateAllAutomationConfigs(c =>
            c.copy(
              // Need to disable triggers so workflows stay open
              enableDsoGovernance = false,
              enableClosedRoundArchival = false,
            )
          ) andThen updateAutomationConfig(ConfigurableApp.Sv)(
            _.withResumedTrigger[AssignTrigger]
              .withPausedTrigger[AmuletConfigReassignmentTrigger]
              .withResumedTrigger[TransferFollowTrigger]
              // DsoRules gets replaced during create phase with this on
              .withPausedTrigger[ElectionRequestTrigger]
              // concurrent modification of AmuletRules can cause tests to fail with `LOCAL_VERDICT_INACTIVE_CONTRACTS` (see #13939)
              .withPausedTrigger[PruneAmuletConfigScheduleTrigger]
              // TODO(#10297): re-enable once that trigger is compatible with soft domain-migrations
              .withPausedTrigger[SubmitSvStatusReportTrigger]
              .withPausedTrigger[ReceiveSvRewardCouponTrigger]
          ))(config),
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

  private[this] val mostDistantPossibleExpiry =
    com.digitalasset.daml.lf.data.Time.Timestamp.MaxValue.toInstant

  "scheduled global domain upgrade happens" in { implicit env =>
    initDsoWithSv1Only() withClue "spin up Dso"
    val ledgerBeginSv1 = sv1Backend.participantClient.ledger_api.state.end()
    val sv1WalletUser = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)

    val timeUntilNewRule = 5.seconds

    // If you fail here, see class scaladoc.
    val globalUpgradeId = sv1Backend.participantClient.domains.id_of(
      globalUpgradeDomain
    ) withClue "find the global-upgrade domain ID"

    val sv1Party = sv1Backend.getDsoInfo().svParty

    // host the dso party as it's stored in the domain stores only
    sv1Backend.appState.participantAdminConnection
      .ensureInitialPartyToParticipant(
        TopologyStoreId.DomainStore(globalUpgradeId),
        dsoParty,
        sv1Backend.participantClientWithAdminToken.id,
      )
      .futureValue
      .discard

    val (previousGlobalId, amuletRulesCid) = clue("change amuletconfig to migrate domains") {
      inside(sv1ScanBackend.getAmuletRules()) {
        case ContractWithState(firstAmuletRules, Assigned(global1)) =>
          val now = env.environment.clock.now
          val currentSchedule = firstAmuletRules.payload.configSchedule
          val activeSynchronizerId =
            AmuletConfigSchedule(currentSchedule)
              .getConfigAsOf(now)
              .decentralizedSynchronizer
              .activeSynchronizer

          globalUpgradeId.toProtoPrimitive should not be activeSynchronizerId
          global1.toProtoPrimitive shouldBe activeSynchronizerId

          val upgradeAfterTick = new Tuple2(
            env.environment.clock.now.add(timeUntilNewRule.toJava).toInstant,
            mkUpdatedAmuletConfig(
              currentSchedule,
              defaultTickDuration,
              nextDomainId = Some(globalUpgradeId),
            ),
          )

          // if fails on "The insertion is scheduled in the future",
          // timeUntilNewRule may not be long enough
          val setScheduleResult = cleanAndAddNewSchedule(
            AssignedContract(firstAmuletRules, global1),
            upgradeAfterTick,
          ) withClue "set config schedule with upgraded domain"

          eventually() {
            inside(sv1ScanBackend.getAmuletRules()) {
              case ContractWithState(secondAmuletRules, _) =>
                secondAmuletRules.contractId should not be firstAmuletRules.contractId
                setScheduleResult shouldBe secondAmuletRules.contractId
            }
          }
          (global1, setScheduleResult)
      }
    }

    clue("trigger AmuletRules cache invalidation right after config change") {
      eventually() {
        try sv1WalletClient.tap(1)
        catch {
          case e: com.digitalasset.canton.console.CommandFailure => fail(e)
        }
      }
    }

    val dsoRules = sv1Backend.getDsoInfo().dsoRules
    val dsoRulesCid = dsoRules.contractId

    def nonEmptyOnSv1[
        TCid <: ContractId[T],
        T <: Template,
    ](companion: Contract.Companion.Template[TCid, T]) =
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(companion)(dsoParty)
        .nonEmpty

    def exerciseDso[T](update: Update[T]) =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty, sv1Party),
          readAs = Seq.empty,
          update = update,
          domainId = Some(previousGlobalId),
        )

    def createSampleAndEnsurePresence[
        TCid <: ContractId[T],
        T <: Template,
    ](companion: Contract.Companion.Template[TCid, T])(payload: T): TCid = {
      val (created, _) = actAndCheck(
        s"create sample ${companion.getTemplateIdWithPackageId.getEntityName}",
        exerciseDso(payload.create()),
      )(
        s"ensure ${companion.getTemplateIdWithPackageId.getEntityName} is there",
        _ => nonEmptyOnSv1(companion),
      )
      companion.toContractId(new ContractId(created.contractId.contractId))
    }

    val dummyRound = new splice.types.Round(42)
    val dummyDecimal = new java.math.BigDecimal(42)

    clue("create governance contracts of various kinds") {
      actAndCheck(
        "create VoteRequest",
        sv1Backend.createVoteRequest(
          sv1Party.toProtoPrimitive,
          new dsorules.actionrequiringconfirmation.ARC_DsoRules(
            new dsorules.dsorules_actionrequiringconfirmation.SRARC_AddSv(
              new dsorules.DsoRules_AddSv(
                "alice",
                "Alice",
                SvUtil.DefaultSV1Weight,
                "alice-participant-id",
                dummyRound,
              )
            )
          ),
          "url",
          "description",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        ),
      )(
        "VoteRequest and Vote should be there",
        _ =>
          inside(sv1Backend.listVoteRequests()) { case Seq(onlyReq) =>
            sv1Backend.lookupVoteRequest(onlyReq.contractId).payload.votes should have size 1
          },
      )

      actAndCheck(
        "create sample ElectionRequest",
        exerciseDso(
          dsoRulesCid.exerciseDsoRules_RequestElection(
            sv1Party.toProtoPrimitive,
            new dsorules.electionrequestreason.ERR_OtherReason("watch the request get migrated"),
            Seq(sv1Party.toProtoPrimitive).asJava,
          )
        ),
      )("ElectionRequest should be there", _ => nonEmptyOnSv1(dsorules.ElectionRequest.COMPANION))

      actAndCheck(
        "create sample Confirmation",
        exerciseDso(
          dsoRulesCid.exerciseDsoRules_ConfirmAction(
            sv1Party.toProtoPrimitive,
            new dsorules.actionrequiringconfirmation.ARC_DsoRules(
              new dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv(
                new dsorules.DsoRules_OffboardSv("nonsense")
              )
            ),
          )
        ),
      )("ensure Confirmation is there", _ => nonEmptyOnSv1(dsorules.Confirmation.COMPANION))

      actAndCheck(
        "create sample SvOnboardingRequest",
        exerciseDso(
          dsoRulesCid.exerciseDsoRules_StartSvOnboarding(
            "irrelevant name",
            sv1Party.toProtoPrimitive, // irrelevant party
            "PAR::sv::1220f3e2",
            "irrelevant token",
            sv1Party.toProtoPrimitive,
          )
        ),
      )("ensure SvOnboardingRequest is there", _ => nonEmptyOnSv1(so.SvOnboardingRequest.COMPANION))

      createSampleAndEnsurePresence(vo.UsedSecret.COMPANION)(
        new vo.UsedSecret(
          sv1Party.toProtoPrimitive,
          "irrelevant secret",
          sv1ValidatorBackend.getValidatorPartyId().toProtoPrimitive,
        )
      )

      createSampleAndEnsurePresence(so.SvOnboardingConfirmed.COMPANION)(
        new so.SvOnboardingConfirmed(
          sv1Party.toProtoPrimitive,
          "irrelevant name",
          SvUtil.DefaultSV1Weight,
          "PAR::sv::1220f3e2",
          "observing domain migration",
          dsoParty.toProtoPrimitive,
          mostDistantPossibleExpiry,
        )
      )
    }

    clue("create DSO-signed amulet contracts of various kinds") {
      val (oldestRound, newestRound) = {
        val rounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._1
        (rounds.headOption.value, rounds.lastOption.value)
      }

      createSampleAndEnsurePresence(splice.round.IssuingMiningRound.COMPANION)(
        new splice.round.IssuingMiningRound(
          dsoParty.toProtoPrimitive,
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

      createSampleAndEnsurePresence(splice.round.ClosedMiningRound.COMPANION)(
        new splice.round.ClosedMiningRound(
          dsoParty.toProtoPrimitive,
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
        exerciseDso(amuletRulesCid.exerciseAmuletRules_DevNet_FeatureApp(sv1Party.toProtoPrimitive)),
      )(
        "ensure FeaturedAppRight is there",
        _ => nonEmptyOnSv1(splice.amulet.FeaturedAppRight.COMPANION),
      )

      createSampleAndEnsurePresence(splice.amulet.UnclaimedReward.COMPANION)(
        new splice.amulet.UnclaimedReward(dsoParty.toProtoPrimitive, dummyDecimal)
      )
    }

    val dummyDistantRelTime = new RelTime(1000000000000L)

    // TODO (#8386) revert 8315's da0c91c29abf for directory stubs

    clue("create app-manager contracts of various kinds") {
      import org.lfdecentralizedtrust.splice.codegen.java.splice.appmanager.store as appManagerCodegen
      import org.lfdecentralizedtrust.splice.http.v0.definitions as httpdefs
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

    def protectAppRewardCoupons = sv1Backend.dsoDelegateBasedAutomation
      .trigger[
        org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpireRewardCouponsTrigger
      ]

    val subscriptionRequestCid = clue("create user wallet contracts of various kinds") {
      val dso = dsoParty.toProtoPrimitive
      val validator = sv1ValidatorBackend.getValidatorPartyId()
      val provider = sv1WalletUser
      val maxTimestamp = com.digitalasset.daml.lf.data.Time.Timestamp.MaxValue.toInstant

      protectAppRewardCoupons.pause().futureValue

      createSampleAndEnsurePresence(splice.amulet.AppRewardCoupon.COMPANION)(
        new splice.amulet.AppRewardCoupon(
          dsoParty.toProtoPrimitive,
          provider.toProtoPrimitive,
          false,
          dummyDecimal,
          dummyRound,
        )
      )
      val lockedAmuletCid = loggerFactory.assertLogs(
        createSampleAndEnsurePresence(splice.amulet.LockedAmulet.COMPANION)(
          new splice.amulet.LockedAmulet(
            new splice.amulet.Amulet(
              dsoParty.toProtoPrimitive,
              provider.toProtoPrimitive,
              new splice.fees.ExpiringAmount(
                dummyDecimal,
                dummyRound,
                new splice.fees.RatePerRound(dummyDecimal),
              ),
            ),
            new splice.expiry.TimeLock(
              java.util.List.of(),
              maxTimestamp,
            ),
          )
        ),
        _.errorMessage should include(
          "Unexpected locked amulet create event"
        ),
      )

      createSampleAndEnsurePresence(splice.amulet.ValidatorRewardCoupon.COMPANION)(
        new splice.amulet.ValidatorRewardCoupon(
          dsoParty.toProtoPrimitive,
          validator.toProtoPrimitive,
          dummyDecimal,
          dummyRound,
        )
      )

      createSampleAndEnsurePresence(splice.validatorlicense.ValidatorFaucetCoupon.COMPANION)(
        new splice.validatorlicense.ValidatorFaucetCoupon(
          dsoParty.toProtoPrimitive,
          validator.toProtoPrimitive,
          dummyRound,
        )
      )

      createSampleAndEnsurePresence(
        splice.validatorlicense.ValidatorLivenessActivityRecord.COMPANION
      )(
        new splice.validatorlicense.ValidatorLivenessActivityRecord(
          dsoParty.toProtoPrimitive,
          validator.toProtoPrimitive,
          dummyRound,
        )
      )

      createSampleAndEnsurePresence(splice.amulet.SvRewardCoupon.COMPANION)(
        new splice.amulet.SvRewardCoupon(
          dsoParty.toProtoPrimitive,
          sv1Party.toProtoPrimitive,
          sv1Party.toProtoPrimitive,
          dummyRound,
          dsoRules.payload.svs.get(sv1Party.toProtoPrimitive).svRewardWeight,
        )
      )

      val appPaymentRequestCid =
        createSampleAndEnsurePresence(spw.payment.AppPaymentRequest.COMPANION)(
          new spw.payment.AppPaymentRequest(
            sv1WalletUser.toProtoPrimitive,
            java.util.List.of(),
            provider.toProtoPrimitive,
            dso,
            maxTimestamp,
            "irrelevant description",
          )
        )

      createSampleAndEnsurePresence(spw.payment.AcceptedAppPayment.COMPANION)(
        new spw.payment.AcceptedAppPayment(
          sv1WalletUser.toProtoPrimitive,
          java.util.List.of,
          provider.toProtoPrimitive,
          dso,
          lockedAmuletCid,
          dummyRound,
          appPaymentRequestCid,
        )
      )

      val dummyPaymentAmount =
        new spw.payment.PaymentAmount(dummyDecimal, spw.payment.Unit.AMULETUNIT)

      val dummySubscriptionData = new spw.subscriptions.SubscriptionData(
        sv1WalletUser.toProtoPrimitive,
        dso,
        provider.toProtoPrimitive,
        dso,
        "irrelevant description",
      )

      val subscriptionRequestCid =
        createSampleAndEnsurePresence(spw.subscriptions.SubscriptionRequest.COMPANION)(
          new spw.subscriptions.SubscriptionRequest(
            dummySubscriptionData,
            new spw.subscriptions.SubscriptionPayData(
              dummyPaymentAmount,
              dummyDistantRelTime,
              dummyDistantRelTime,
            ),
          )
        )

      createSampleAndEnsurePresence(spw.subscriptions.Subscription.COMPANION)(
        new spw.subscriptions.Subscription(dummySubscriptionData, subscriptionRequestCid)
      )

      createSampleAndEnsurePresence(spw.transferoffer.TransferOffer.COMPANION)(
        new spw.transferoffer.TransferOffer(
          dso,
          sv1WalletUser.toProtoPrimitive,
          dso,
          dummyPaymentAmount,
          "irrelevant description",
          maxTimestamp,
          "irrelevant tracking id",
        )
      )

      createSampleAndEnsurePresence(spw.transferoffer.AcceptedTransferOffer.COMPANION)(
        new spw.transferoffer.AcceptedTransferOffer(
          dso,
          sv1WalletUser.toProtoPrimitive,
          dso,
          dummyPaymentAmount,
          maxTimestamp,
          "irrelevant tracking id",
        )
      )

      subscriptionRequestCid
    }

    clue("create DSO-signed ANS contracts of various kinds") {
      import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultAnsConfig

      val dso = dsoParty.toProtoPrimitive

      createSampleAndEnsurePresence(ans.AnsRules.COMPANION)(
        new ans.AnsRules(dso, defaultAnsConfig())
      )

      createSampleAndEnsurePresence(ans.AnsEntry.COMPANION)(
        new ans.AnsEntry(
          dso,
          dso,
          "irrelevant name",
          "urn:example.com",
          "irrelevant description",
          mostDistantPossibleExpiry,
        )
      )

      createSampleAndEnsurePresence(ans.AnsEntryContext.COMPANION)(
        new ans.AnsEntryContext(
          dso,
          dso,
          "irrelevant name",
          "urn:example.com",
          "irrelevant description",
          subscriptionRequestCid,
        )
      )
    }

    sv1Backend.dsoAutomation.trigger[AmuletConfigReassignmentTrigger].resume()
    // note that getDsoInfo can 404 transiently from this point on as
    // DsoRules is being reassigned

    clue("see whether the dsorules moves") {
      eventually() {
        val cid: String = dsoRulesCid.contractId
        sv1ValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .lookup_contract_domain(dsoParty, Set(cid)) shouldBe Map(
          cid -> globalUpgradeId
        )
      }
    }

    clue("see whether amuletrules follows dsorules") {
      eventually() {
        sv1ScanBackend.getAmuletRules().state shouldBe Assigned(globalUpgradeId)
      }
    }

    import language.existentials
    type FilterableCompanion =
      TemplateCompanion[_ <: CodegenContract[TCid, Data], TCid, Data] forSome {
        type Data <: Template
        type TCid <: ContractId[Data]
      }

    def c(fc: FilterableCompanion, queryParty: PartyId = dsoParty) =
      (fc, queryParty)

    def allContractsMigrated(rows: (FilterableCompanion, PartyId)*) = {
      val companions = Table[JIdentifier, FilterableCompanion, PartyId](
        ("template", "companion", "querying party"),
        rows.map { case (c, p) => (c.getTemplateIdWithPackageId, c, p) }*
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

          tForEvery(Table("contract ID", contractIds*)) { cid =>
            domains.get(cid) shouldBe Some(globalUpgradeId)
          }
        }
      }
    }

    clue("see whether governance contracts follow dsorules") {
      import org.lfdecentralizedtrust.splice.sv.store.SvSvStore.templatesMovedByMyAutomation as templatesMovedBySvAutomation
      allContractsMigrated(
        c(dsorules.VoteRequest.COMPANION),
        c(dsorules.Confirmation.COMPANION),
        c(dsorules.ElectionRequest.COMPANION),
        c(so.SvOnboardingRequest.COMPANION),
        c(so.SvOnboardingConfirmed.COMPANION),
      )
      // only have the sv party as a stakeholder
      allContractsMigrated(templatesMovedBySvAutomation.map(c(_, sv1Party))*)
    }

    // below will wait for AmuletRules to move, which happens at least a
    // trigger fire later than DsoRules moves

    clue(
      "see whether amulet contracts signed only by DSO (in particular mining rounds) follow dsorules"
    ) {
      import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
      allContractsMigrated(
        SvDsoStore.amuletRulesFollowers
          filterNot Set(
            spw.subscriptions.TerminatedSubscription.COMPANION, // TODO (#8386)
            splice.round.SummarizingMiningRound.COMPANION, // TODO (#10705)
            splice.amuletrules.TransferPreapproval.COMPANION,
          )
          map (c(_)): _*
      )
    }

    clue(
      "wait for scan to yield transferred AmuletRules. "
        + "This does not mean the cache was invalidated, though"
    ) {
      eventually() {
        sv1ScanBackend.getAmuletRules().state shouldBe Assigned(globalUpgradeId)
      }
    }

    // TODO (#8135) tap here instead to check improved cache invalidation

    clue("see whether amulet/wallet contracts signed by validator follow amuletrules") {
      val sv1ValidatorParty = sv1ValidatorBackend.getValidatorPartyId()
      import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore.templatesMovedByMyAutomation as templatesMovedByValidatorAutomation
      allContractsMigrated(
        (templatesMovedByValidatorAutomation(true) filterNot Set(
          splice.amuletrules.ExternalPartySetupProposal.COMPANION
        )) map (c(_, sv1ValidatorParty)): _*
      )
    }

    clue("see whether amulet/wallet contracts signed by wallet user follow amuletrules") {
      import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore.templatesMovedByMyAutomation as templatesMovedByUserWalletAutomation
      allContractsMigrated(
        templatesMovedByUserWalletAutomation
          filterNot Set( // TODO (#8386) remove filtering
            spw.subscriptions.SubscriptionInitialPayment.COMPANION,
            spw.subscriptions.SubscriptionIdleState.COMPANION,
            spw.subscriptions.SubscriptionPayment.COMPANION,
            spw.transferpreapproval.TransferPreapprovalProposal.COMPANION,
          )
          map (c(_, sv1WalletUser)): _*
      )
    }

    eventually() {
      compareHistoryViaScanApi(
        ledgerBeginSv1,
        sv1Backend,
        scancl("sv1ScanClient"),
      )
    }

    protectAppRewardCoupons.resume()
  }

  private[this] def cleanAndAddNewSchedule(
      start: AssignedContract[
        splice.amuletrules.AmuletRules.ContractId,
        splice.amuletrules.AmuletRules,
      ],
      newSchedule: Tuple2[Instant, splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD]],
  )(implicit fp: FixtureParam): splice.amuletrules.AmuletRules.ContractId = {
    sv1ScanBackend
      .getAmuletRules()
      .payload
      .configSchedule
      .futureValues
      .forEach(config => {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(dsoParty),
            readAs = Seq.empty,
            update = start.contractId
              .exerciseAmuletRules_RemoveFutureAmuletConfigSchedule(config._1),
            domainId = Some(start.domain),
          )
          .exerciseResult
      })
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(dsoParty),
        readAs = Seq.empty,
        update = start.contractId.exerciseAmuletRules_AddFutureAmuletConfigSchedule(newSchedule),
        domainId = Some(start.domain),
      )
      .exerciseResult
      .newAmuletRules
  }
}
