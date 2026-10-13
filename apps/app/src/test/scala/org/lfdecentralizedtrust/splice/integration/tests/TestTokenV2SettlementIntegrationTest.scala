package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.event.CreatedEvent.toJavaProto as createdEventToJavaProto
import com.daml.ledger.api.v2.value.Identifier.toJavaProto as identifierToJavaProto
import com.daml.ledger.javaapi.data.CreatedEvent
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv2.AllocationFactory_Allocate
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv2.allocationinstructionresult_output.AllocationInstructionResult_Completed
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv2.AllocationRequest_Accept
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.anyvalue.AV_ContractId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv2.transferinstructionresult_output.TransferInstructionResult_Completed
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingappv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingappv2.settlementbatch.SettlementBatchV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.tokens.testtokenv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.tokens.testtokenv2.TokenRules as TokenV2Rules
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.tokens.testtokenv2.holding.Token as TestTokenV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.util.token.wallet.batchingutilityv2.tokenstandardaction.{
  TSA_AllocationFactory_AllocateV2,
  TSA_AllocationRequest_AcceptV2,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.util.token.wallet.batchingutilityv2.tokenstandardactionresult.{
  TSAR_AllocationInstructionResultV2,
  TSAR_AllocationRequest_AcceptV2Result,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.util.token.wallet.batchingutilityv2.{
  BatchingUtility,
  ChoiceCall,
  HoldingMap,
  ScopedAccount,
  BatchingUtility as BatchingUtilityV2,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  bumpUrl,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.http.v0.definitions.EventHistoryItem
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardV2TestUtil.ExpectedTrafficCost
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import org.lfdecentralizedtrust.splice.sv.config.ExpectedValidatorOnboardingConfig
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*

// Not checking Daml compatibility because it doesn't make sense with TestTokenV2
@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class TestTokenV2SettlementIntegrationTest
    extends IntegrationTest
    with TokenStandardTest
    with WalletTestUtil
    with HasActorSystem
    with TimeTestUtil
    with HasExecutionContext
    with TriggerTestUtil
    with StandaloneCanton
    with TokenStandardV2TestUtil {

  override def dbsSuffix: String = "test_token_v2_settlement"
  val dbName = s"participant_alice_validator_${dbsSuffix}"
  override def usesDbs = Seq(dbName) ++ super.usesDbs

  // Can sometimes be unhappy when doing funky `withCanton` things; disabling them for simplicity
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreForTemplateIds(
      Seq(TestTokenV2.TEMPLATE_ID)
    )

  private val testTokenV2DarPath = Paths
    .get(
      "token-standard/examples/splice-test-token-v2/.daml/dist/splice-test-token-v2-current.dar"
    )
    .toAbsolutePath
    .toString

  private val usdcInstrumentName = "USDC"

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithLocalValidator(this.getClass.getSimpleName)
      .withoutAliceValidatorConnectingToSplitwell
      .withSequencerConnectionsFromScanDisabled()
      .addConfigTransform((_, config) => {
        // We copy the already-existing definition of aliceValidatorLocal for testTokenValidatorLocal
        val aliceValidatorLocal = config.validatorApps.getOrElse(
          InstanceName.tryCreate("aliceValidatorLocal"),
          throw new RuntimeException("aliceValidatorLocal not found"),
        )
        config.copy(
          svApps = config.svApps.map { case (instanceName, svApp) =>
            instanceName -> svApp.copy(expectedValidatorOnboardings =
              svApp.expectedValidatorOnboardings :+ ExpectedValidatorOnboardingConfig(
                "aliceExtraValidator"
              )
            )
          },
          validatorApps =
            config.validatorApps + (InstanceName.tryCreate("testTokenValidatorLocal") ->
              aliceValidatorLocal.copy(
                adminApi = aliceValidatorLocal.adminApi
                  .copy(internalPort = Some(aliceValidatorLocal.adminApi.port + 22_000)),
                onboarding =
                  aliceValidatorLocal.onboarding.map(_.copy(secret = "aliceExtraValidator")),
                // party hint has a fixed length, which the measured traffic costs depend on.
                validatorPartyHint =
                  Some(f"testtoken-validator-${scala.util.Random.nextInt(1000000)}%06d"),
              )),
          walletAppClients = config.walletAppClients + (
            InstanceName.tryCreate("aliceValidatorLocalWallet") -> {
              val aliceValidatorWalletConfig =
                config.walletAppClients(InstanceName.tryCreate("aliceValidatorWallet"))

              aliceValidatorWalletConfig
                .copy(
                  adminApi = aliceValidatorWalletConfig.adminApi
                    .copy(url = bumpUrl(22_000, aliceValidatorWalletConfig.adminApi.url.toString()))
                )
            }
          ),
        )
      })
      // We'll advance manually after featuring to guarantee that app activity records are computed
      .addConfigTransforms(
        (_, config) =>
          ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(
            config
          ),
        (_, config) =>
          ConfigTransforms.updateInitialExternalPartyConfigStateTickDuration(
            NonNegativeFiniteDuration.ofMillis(500)
          )(config),
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(sv => {
          sv.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        })(config)
      )
  }

  def ttAdminValidator(implicit env: SpliceTestConsoleEnvironment) = v("testTokenValidatorLocal")

  private def withValidatorsInitializedForTest[T](
      f: () => T
  )(implicit env: SpliceTestConsoleEnvironment): T = {
    sv1ValidatorBackend.startSync() // hosts DSO
    Seq(
      aliceValidatorBackend, // hosts Alice
      bobValidatorBackend, // hosts Bob
      splitwellValidatorBackend, // hosts the venue party
      ttAdminValidator, // hosts the ttadmin
    ).foreach { validatorBackend =>
      validatorBackend.startSync()
      validatorBackend.participantClient.upload_dar_unless_exists(tokenStandardV2TestDarPath)
      validatorBackend.participantClient
        .upload_dar_unless_exists(testTokenV2DarPath)
    }
    try {
      f()
    } finally {
      Seq(
        sv1ValidatorBackend,
        aliceValidatorBackend,
        bobValidatorBackend,
        splitwellValidatorBackend,
        ttAdminValidator,
      ).foreach(_.stop())
    }
  }

  "TestTokenV2 should be settleable" in { implicit env =>
    initDso()
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf"
      ),
      Seq(),
      "test_token_v2_settlement",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> ttAdminValidator.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> dbName,
    ) {
      withValidatorsInitializedForTest { () =>
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
        val venueValidator = splitwellValidatorBackend
        val venueParty = PartyId.tryFromProtoPrimitive(splitwellWalletClient.userStatus().party)
        val ttAdminParty = ttAdminValidator.getValidatorPartyId()
        val registry = new TestTokenV2Registry(ttAdminParty, ttAdminValidator)

        // Give alice some CC
        aliceWalletClient.tap(1000)
        val aliceCCBalanceBefore = eventually() {
          val balance = aliceWalletClient.balance().unlockedQty
          balance should be > BigDecimal(0)
          balance
        }

        // make venue and ttadmin featured app parties
        splitwellWalletClient.selfGrantFeaturedAppRight()
        aliceValidatorWalletLocalClient.selfGrantFeaturedAppRight()
        // We need to make sure that after this block, the oldest active OpenMiningRound
        // has an `openAt` time that is after the time the first OpenMiningRound was archived,
        // otherwise the ingestion start approximation in
        // `DbScanRewardsReferenceStore.lookupActiveOpenMiningRounds` might filter out the
        // OpenMiningRound contract and AppActivityComputation won't ingest activity records.
        clue("Advance rounds") {
          advanceRoundsByOneTickViaAutomation() // archives round 0, open rounds are 1-3
          advanceRoundsByOneTickViaAutomation() // archives round 1, open rounds are 2-4
          advanceRoundsByOneTickViaAutomation() // archives round 2, open rounds are 3-5
          advanceRoundsByOneTickViaAutomation() // archives round 3, open rounds are 4-6
        }

        // Create BatchingUtilityV2 contracts for Alice and Bob
        val batchingUtilityIds: Map[PartyId, BatchingUtility.ContractId] =
          Map(aliceValidatorBackend -> aliceParty, bobValidatorBackend -> bobParty).map {
            case (validatorBackend, party) =>
              party -> validatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitWithResult(
                  userId = validatorBackend.config.ledgerApiUser,
                  actAs = Seq(party),
                  readAs = Seq(party),
                  update = BatchingUtilityV2.create(party.toProtoPrimitive),
                )
                .contractId
          }

        // Create TokenRules for ttadmin
        val tokenRulesId = ttAdminValidator.participantClient.ledger_api_extensions.commands
          .submitWithResult(
            userId = ttAdminValidator.config.ledgerApiUser,
            actAs = Seq(ttAdminParty),
            readAs = Seq(ttAdminParty),
            update = TokenV2Rules.create(ttAdminParty.toProtoPrimitive),
          )
          .contractId

        // Call TokenRules_OfferMint to offer 100 USDC to Bob
        val bobConfigAccount = new testtokenv2.accountconfig.AccountConfig(
          ttAdminParty.toProtoPrimitive,
          basicAccount(bobParty),
          new testtokenv2.accountconfig.PartyConfig(true, true),
          new testtokenv2.accountconfig.PartyConfig(false, false),
        )
        val bobOfferMintAmount = 100
        ttAdminValidator.participantClient.ledger_api_extensions.commands
          .submitJava(
            userId = ttAdminValidator.config.ledgerApiUser,
            actAs = Seq(ttAdminParty),
            commands = tokenRulesId
              .exerciseTokenRules_OfferMint(
                basicAccount(bobParty),
                BigDecimal(bobOfferMintAmount).bigDecimal,
                new holdingv2.InstrumentId(ttAdminParty.toProtoPrimitive, "USDC"),
                Instant.now(),
                bobConfigAccount,
              )
              .commands()
              .asScala
              .toSeq,
          )

        // Bob accepts
        val transferInstruction = eventually() {
          Contract
            .fromCreatedEvent(transferinstructionv2.TransferInstruction.INTERFACE)(
              CreatedEvent.fromProto(
                createdEventToJavaProto(
                  bobValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
                    .of_party(
                      party = bobParty,
                      filterInterfaces =
                        Seq(transferinstructionv2.TransferInstruction.TEMPLATE_ID).map(templateId =>
                          TemplateId(
                            templateId.getPackageId,
                            templateId.getModuleName,
                            templateId.getEntityName,
                          )
                        ),
                    )
                    .loneElement
                    .event
                )
              )
            )
            .valueOrFail("Failed to read transferinstructionv2.TransferInstruction")
        }
        val acceptContext =
          registry.getContext(
            transferInstruction.payload.transfer.inputHoldingCids.asScala.toSeq
          )
        val transferResult =
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = bobValidatorBackend.config.ledgerApiUser,
              actAs = Seq(bobParty),
              readAs = Seq(bobParty),
              update = transferInstruction.contractId.exerciseTransferInstruction_Accept(
                java.util.List.of(bobParty.toProtoPrimitive),
                new metadatav1.ExtraArgs(acceptContext.choiceContext, emptyMetadata),
              ),
              disclosedContracts = acceptContext.disclosedContracts,
            )
        transferResult.exerciseResult.output match {
          case completed: TransferInstructionResult_Completed =>
            completed.receiverHoldingCids.asScala.toSeq
          case other => fail(s"Offer mint was not completed: $other")
        }

        // Venue creates the trade
        val (createTradeTx, otcTrade) = actAndCheck(
          "Venue creates OTC Trade", {
            venueValidator.participantClientWithAdminToken.ledger_api_extensions.commands
              .submitJava(
                actAs = Seq(venueParty),
                commands = new tradingappv2.OTCTrade(
                  venueParty.toProtoPrimitive,
                  Seq(
                    // Alice -> Bob: 100 CC
                    new tradingappv2.TradeLeg(
                      dsoParty.toProtoPrimitive,
                      new allocationv2.TransferLeg(
                        "alicetobob100CC",
                        basicAccount(aliceParty),
                        basicAccount(bobParty),
                        BigDecimal(100).bigDecimal,
                        amuletInstrumentIdName,
                        emptyMetadata,
                      ),
                    ),
                    // Bob -> Alice: 15 USDC
                    new tradingappv2.TradeLeg(
                      ttAdminParty.toProtoPrimitive,
                      new allocationv2.TransferLeg(
                        "bobtoalice15USDC",
                        basicAccount(bobParty),
                        basicAccount(aliceParty),
                        BigDecimal(15).bigDecimal,
                        usdcInstrumentName,
                        emptyMetadata,
                      ),
                    ),
                    // Alice -> Venue: 0.2 USDC
                    new tradingappv2.TradeLeg(
                      ttAdminParty.toProtoPrimitive,
                      new allocationv2.TransferLeg(
                        "alicetovenue0.2USDC",
                        basicAccount(aliceParty),
                        basicAccount(venueParty),
                        BigDecimal(0.2).bigDecimal,
                        usdcInstrumentName,
                        emptyMetadata,
                      ),
                    ),
                  ).asJava,
                  Instant.now(),
                  Instant.now().plusSeconds(60L),
                  java.util.Optional.of(Instant.now().plusSeconds(180L)),
                )
                  .create()
                  .commands()
                  .asScala
                  .toSeq,
              )
          },
        )(
          "There exists a trade visible to the venue's participant",
          _ =>
            venueValidator.participantClientWithAdminToken.ledger_api_extensions.acs
              .awaitJava(tradingappv2.OTCTrade.COMPANION)(
                venueParty
              ),
        )

        val (createAllocationRequestsTx, (bobAllocationRequest, aliceAllocationRequest)) =
          actAndCheck(
            "Venue creates allocation requests", {
              venueValidator.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitJava(
                  actAs = Seq(venueParty),
                  commands = otcTrade.id
                    .exerciseOTCTrade_RequestAllocations()
                    .commands()
                    .asScala
                    .toSeq,
                )
            },
          )(
            "Sender and receiver see the allocation requests",
            _ => {
              val bobAllocationRequest = inside(
                bobWalletClient.listAllocationRequests()
              ) {
                case (allocationRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest) +: Nil =>
                  allocationRequest
              }
              val aliceAllocationRequest = inside(
                aliceWalletClient.listAllocationRequests()
              ) {
                case (allocationRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest) +: Nil =>
                  allocationRequest
              }

              (bobAllocationRequest, aliceAllocationRequest)
            },
          )

        val (aliceAllocationCids, aliceAllocateTx) = clue(
          "Alice uses the BatchingUtilityV2 to create two allocations and accept the allocation request in a single tx"
        ) {
          // UpdateExternalPartyConfigStateTrigger might run concurrently and cause a LOCAL_VERDICT_INACTIVE_CONTRACTS
          // because of the ExternalPartyConfigState being updated.
          // In the real world, we expect the venue to also just retry re-fetching all contexts
          eventuallySucceeds() {
            val batchingUtility = batchingUtilityIds(aliceParty)
            val aliceAmulets = aliceWalletClient
              .list()
              .amulets
              .map(_.contract.contractId.toInterface(holdingv2.Holding.INTERFACE))
            aliceAmulets should have size 1
            val amuletSpec = aliceAllocationRequest.contract.payload.allocations.asScala
              .filter(_.admin == dsoParty.toProtoPrimitive)
              .loneElement
            val usdcSpec = aliceAllocationRequest.contract.payload.allocations.asScala
              .filter(_.admin == ttAdminParty.toProtoPrimitive)
              .loneElement
            val amuletAllocationFactory = sv1ScanBackend.getAllocationFactoryV2(
              new allocationinstructionv2.AllocationFactory_Allocate(
                aliceAllocationRequest.contract.payload.settlement,
                amuletSpec,
                aliceAllocationRequest.contract.payload.requestedAt,
                aliceAmulets.asJava,
                emptyExtraArgs,
                java.util.List.of(aliceParty.toProtoPrimitive),
              )
            )
            val usdcContext = registry.getContext(Seq.empty)
            val aliceAllocateUpdate = batchingUtility
              .exerciseBatchingUtility_ExecuteBatch(
                new HoldingMap(
                  Map(
                    new ScopedAccount(
                      dsoParty.toProtoPrimitive,
                      basicAccount(aliceParty),
                    ) -> Map[String, java.util.List[holdingv2.Holding.ContractId]](
                      amuletInstrumentIdName -> aliceAmulets.asJava
                    ).asJava,
                    new ScopedAccount(
                      ttAdminParty.toProtoPrimitive,
                      basicAccount(aliceParty),
                    ) -> Map
                      .empty[String, java.util.List[holdingv2.Holding.ContractId]]
                      .asJava, // alice has no USDC here yet
                  ).asJava
                ),
                java.util.List.of(
                  new TSA_AllocationFactory_AllocateV2(
                    new ChoiceCall[AllocationFactory_Allocate](
                      new metadatav1.AnyContract.ContractId(
                        amuletAllocationFactory.factoryId.contractId
                      ),
                      amuletAllocationFactory.args,
                    )
                  ),
                  new TSA_AllocationFactory_AllocateV2(
                    new ChoiceCall[AllocationFactory_Allocate](
                      new metadatav1.AnyContract.ContractId(tokenRulesId.contractId),
                      new allocationinstructionv2.AllocationFactory_Allocate(
                        aliceAllocationRequest.contract.payload.settlement,
                        usdcSpec,
                        aliceAllocationRequest.contract.payload.requestedAt,
                        java.util.List.of(),
                        new metadatav1.ExtraArgs(usdcContext.choiceContext, emptyMetadata),
                        java.util.List.of(aliceParty.toProtoPrimitive),
                      ),
                    )
                  ),
                  new TSA_AllocationRequest_AcceptV2(
                    new ChoiceCall[AllocationRequest_Accept](
                      new metadatav1.AnyContract.ContractId(
                        aliceAllocationRequest.contract.contractId.contractId
                      ),
                      new AllocationRequest_Accept(
                        java.util.List.of(aliceParty.toProtoPrimitive),
                        amuletAllocationFactory.args.extraArgs,
                      ),
                    )
                  ),
                ),
                true,
              )
            val aliceAllocateTx =
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitJava(
                  userId = aliceValidatorBackend.config.ledgerApiUser,
                  actAs = Seq(aliceParty),
                  readAs = Seq(aliceParty),
                  commands = aliceAllocateUpdate.commands().asScala.toSeq,
                  disclosedContracts =
                    amuletAllocationFactory.disclosedContracts ++ usdcContext.disclosedContracts,
                )

            val aliceAllocationCids = SpliceLedgerConnection
              .decodeExerciseResult(
                aliceAllocateUpdate,
                aliceAllocateTx,
              )
              .exerciseResult
              .actionResults
              .asScala
              .map {
                case _: TSAR_AllocationRequest_AcceptV2Result => None
                case v: TSAR_AllocationInstructionResultV2 =>
                  v.allocationInstructionResultValue.output match {
                    case completed: AllocationInstructionResult_Completed =>
                      Some(completed.allocationCid)
                    case other =>
                      fail(s"Expected AllocationInstructionResult_Completed but got $other")
                  }
                case other =>
                  fail(s"Expected TSAR_AllocationResultV2 but got $other")
              }
              .collect { case Some(cid) => cid }

            (aliceAllocationCids, aliceAllocateTx)
          }
        }

        val (bobAllocationCids, bobAllocateTx) = clue(
          "Bob uses the BatchingUtilityV2 to accept the request and create two allocations in a single tx"
        ) {
          // Same UpdateExternalPartyConfigStateTrigger/LOCAL_VERDICT_INACTIVE_CONTRACTS logic
          // as with alice's usage of BatchingUtilityV2 above.
          eventuallySucceeds() {
            val batchingUtility = batchingUtilityIds(bobParty)
            val amuletSpec = bobAllocationRequest.contract.payload.allocations.asScala
              .filter(_.admin == dsoParty.toProtoPrimitive)
              .loneElement
            val usdcSpec = bobAllocationRequest.contract.payload.allocations.asScala
              .filter(_.admin == ttAdminParty.toProtoPrimitive)
              .loneElement
            val amuletAllocationFactory = sv1ScanBackend.getAllocationFactoryV2(
              new allocationinstructionv2.AllocationFactory_Allocate(
                bobAllocationRequest.contract.payload.settlement,
                amuletSpec,
                bobAllocationRequest.contract.payload.requestedAt,
                java.util.List.of(), // bob has no amulets
                emptyExtraArgs,
                java.util.List.of(bobParty.toProtoPrimitive),
              )
            )
            val bobUsdcHoldings = getHoldings(bobParty, bobValidatorBackend)
              .map(_.contractId)
              .map(id => new holdingv2.Holding.ContractId(id))
            val usdcContext = registry.getContext(
              bobUsdcHoldings
            )
            val bobAllocateUpdate = batchingUtility
              .exerciseBatchingUtility_ExecuteBatch(
                new HoldingMap(
                  Map(
                    new ScopedAccount(
                      dsoParty.toProtoPrimitive,
                      basicAccount(bobParty),
                    ) -> Map[String, java.util.List[holdingv2.Holding.ContractId]]().asJava,
                    new ScopedAccount(
                      ttAdminParty.toProtoPrimitive,
                      basicAccount(bobParty),
                    ) -> Map[String, java.util.List[holdingv2.Holding.ContractId]](
                      usdcInstrumentName -> bobUsdcHoldings.asJava
                    ).asJava, // alice has no USDC here yet
                  ).asJava
                ),
                java.util.List.of(
                  new TSA_AllocationFactory_AllocateV2(
                    new ChoiceCall[AllocationFactory_Allocate](
                      new metadatav1.AnyContract.ContractId(
                        amuletAllocationFactory.factoryId.contractId
                      ),
                      amuletAllocationFactory.args,
                    )
                  ),
                  new TSA_AllocationFactory_AllocateV2(
                    new ChoiceCall[AllocationFactory_Allocate](
                      new metadatav1.AnyContract.ContractId(tokenRulesId.contractId),
                      new allocationinstructionv2.AllocationFactory_Allocate(
                        bobAllocationRequest.contract.payload.settlement,
                        usdcSpec,
                        bobAllocationRequest.contract.payload.requestedAt,
                        java.util.List.of(),
                        new metadatav1.ExtraArgs(usdcContext.choiceContext, emptyMetadata),
                        java.util.List.of(bobParty.toProtoPrimitive),
                      ),
                    )
                  ),
                  new TSA_AllocationRequest_AcceptV2(
                    new ChoiceCall[AllocationRequest_Accept](
                      new metadatav1.AnyContract.ContractId(
                        bobAllocationRequest.contract.contractId.contractId
                      ),
                      new AllocationRequest_Accept(
                        java.util.List.of(bobParty.toProtoPrimitive),
                        new metadatav1.ExtraArgs(usdcContext.choiceContext, emptyMetadata),
                      ),
                    )
                  ),
                ),
                true,
              )
            val bobAllocateTx =
              bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitJava(
                  userId = bobValidatorBackend.config.ledgerApiUser,
                  actAs = Seq(bobParty),
                  readAs = Seq(bobParty),
                  commands = bobAllocateUpdate.commands().asScala.toSeq,
                  disclosedContracts =
                    amuletAllocationFactory.disclosedContracts ++ usdcContext.disclosedContracts,
                )

            val bobAllocationCids = SpliceLedgerConnection
              .decodeExerciseResult(
                bobAllocateUpdate,
                bobAllocateTx,
              )
              .exerciseResult
              .actionResults
              .asScala
              .map {
                case _: TSAR_AllocationRequest_AcceptV2Result => None
                case v: TSAR_AllocationInstructionResultV2 =>
                  v.allocationInstructionResultValue.output match {
                    case completed: AllocationInstructionResult_Completed =>
                      Some(completed.allocationCid)
                    case other =>
                      fail(s"Expected AllocationInstructionResult_Completed but got $other")
                  }
                case other =>
                  fail(s"Expected TSAR_AllocationResultV2 but got $other")
              }
              .collect { case Some(cid) => cid }

            (bobAllocationCids, bobAllocateTx)
          }
        }

        val (settleTradeTx, _) = actAndCheck(
          "Venue settles the trade", {
            // Same UpdateExternalPartyConfigStateTrigger/LOCAL_VERDICT_INACTIVE_CONTRACTS logic
            // as with alice's usage of BatchingUtilityV2 above.
            eventuallySucceeds() {
              val allAllocations = {
                venueValidator.participantClientWithAdminToken.ledger_api.state.acs.of_party(
                  party = venueParty,
                  filterInterfaces = Seq(allocationv2.Allocation.TEMPLATE_ID).map(templateId =>
                    TemplateId(
                      templateId.getPackageId,
                      templateId.getModuleName,
                      templateId.getEntityName,
                    )
                  ),
                  includeCreatedEventBlob = true,
                )
              }
              // sanity check
              (bobAllocationCids ++ aliceAllocationCids).foreach { cid =>
                allAllocations
                  .find(_.contractId == cid.contractId)
                  .valueOrFail(s"No allocation found for cid $cid")
              }
              val amuletAllocations =
                allAllocations.filter(_.event.signatories.contains(dsoParty.toProtoPrimitive))
              val usdAllocations =
                allAllocations.filter(_.event.signatories.contains(ttAdminParty.toProtoPrimitive))
              val settleBatch = new allocationv2.SettlementFactory_SettleBatch(
                new allocationv2.SettlementInfo(
                  java.util.List.of(venueParty.toProtoPrimitive),
                  "OTCTrade",
                  java.util.Optional
                    .of(new metadatav1.AnyContract.ContractId(otcTrade.id.contractId)),
                  emptyMetadata,
                ),
                transferLegsFromTrade(otcTrade).asJava,
                allAllocations
                  .map(alloc =>
                    new allocationv2.FinalizedAllocation(
                      new allocationv2.Allocation.ContractId(alloc.contractId),
                      java.util.List.of(),
                      java.util.Optional.empty[java.util.Map[String, java.math.BigDecimal]](),
                    )
                  )
                  .asJava,
                /*actors = */ java.util.List.of(venueParty.toProtoPrimitive),
                emptyExtraArgs,
              )
              val amuletContext = sv1ScanBackend.getSettlementFactoryV2(settleBatch)
              val bobUsdcHoldings = getHoldings(bobParty, bobValidatorBackend)
                .map(_.contractId)
                .map(id => new holdingv2.Holding.ContractId(id))
              bobUsdcHoldings should have size 2
              val usdcContext = registry.getContext(
                bobUsdcHoldings
              )
              venueValidator.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitJava(
                  actAs = Seq(venueParty),
                  commands = otcTrade.id
                    .exerciseOTCTrade_Settle(
                      Map[String, tradingappv2.SettlementBatch](
                        dsoParty.toProtoPrimitive -> new SettlementBatchV2(
                          amuletAllocations
                            .map(alloc => new allocationv2.Allocation.ContractId(alloc.contractId))
                            .asJava,
                          java.util.List.of(),
                          amuletContext.factoryId,
                          amuletContext.args.extraArgs,
                        ),
                        ttAdminParty.toProtoPrimitive -> new SettlementBatchV2(
                          usdAllocations
                            .map(alloc => new allocationv2.Allocation.ContractId(alloc.contractId))
                            .asJava,
                          java.util.List.of(
                            new tradingappv2.MissingAllocation(
                              java.util.Optional.empty(),
                              tokenRulesId.toInterface(
                                allocationinstructionv2.AllocationFactory.INTERFACE
                              ),
                              new allocationinstructionv2.AllocationFactory_Allocate(
                                new allocationv2.SettlementInfo(
                                  java.util.List.of(venueParty.toProtoPrimitive),
                                  "OTCTradeProposal",
                                  java.util.Optional.of(
                                    new metadatav1.AnyContract.ContractId(otcTrade.id.contractId)
                                  ),
                                  emptyMetadata,
                                ),
                                new allocationv2.AllocationSpecification(
                                  ttAdminParty.toProtoPrimitive,
                                  basicAccount(venueParty),
                                  java.util.List.of(
                                    new allocationv2.TransferLegSide(
                                      "alicetovenue0.2USDC",
                                      allocationv2.TransferSide.RECEIVERSIDE,
                                      basicAccount(aliceParty),
                                      BigDecimal(0.2).bigDecimal,
                                      usdcInstrumentName,
                                      emptyMetadata,
                                    )
                                  ),
                                  java.util.Optional.empty(),
                                  java.util.Optional.empty(),
                                  false,
                                  emptyMetadata,
                                ),
                                Instant.now(),
                                java.util.List.of(),
                                new metadatav1.ExtraArgs(usdcContext.choiceContext, emptyMetadata),
                                java.util.List.of(venueParty.toProtoPrimitive),
                              ),
                            )
                          ),
                          new allocationv2.SettlementFactory.ContractId(tokenRulesId.contractId),
                          new metadatav1.ExtraArgs(usdcContext.choiceContext, emptyMetadata),
                        ),
                      ).asJava,
                      java.util.List.of(),
                    )
                    .commands()
                    .asScala
                    .toSeq,
                  disclosedContracts =
                    usdcContext.disclosedContracts ++ amuletContext.disclosedContracts,
                )
            }
          },
        )(
          "The balances are updated",
          _ => {
            aliceWalletClient.balance().unlockedQty should be(aliceCCBalanceBefore - 100)
            bobWalletClient.balance().unlockedQty should be(100)

            getUsdcBalance(bobParty, bobValidatorBackend) should be(bobOfferMintAmount - 15)
            getUsdcBalance(aliceParty, aliceValidatorBackend) should be(15 - 0.2)
            getUsdcBalance(venueParty, venueValidator) should be(0.2)
          },
        )

        // The reference traffic costs were recorded by running this test on Canton 3.6.0
        val events = checkTrafficCosts(
          Seq(
            createTradeTx.getUpdateId -> ExpectedTrafficCost("Create Trade", 3202),
            createAllocationRequestsTx.getUpdateId -> ExpectedTrafficCost(
              "Create Allocation Requests",
              8676,
            ),
            aliceAllocateTx.getUpdateId -> ExpectedTrafficCost("Alice Allocations", 14795),
            bobAllocateTx.getUpdateId -> ExpectedTrafficCost("Bob Allocations", 17333),
            settleTradeTx.getUpdateId -> ExpectedTrafficCost("Settle Trade", 22460),
          )
        )
        events.foreach { case (action, item) =>
          withClue(action)(item.appActivityRecords should be(defined))
        }
        val eventsJson =
          events.map { case (action, item) =>
            action -> EventHistoryItem.encodeEventHistoryItem(item)
          }
        val json = io.circe.JsonObject(eventsJson*)
        val savePath =
          java.io.File.createTempFile("test_token_v2_settlement_results", ".json").toPath
        Files.writeString(savePath, json.toJson.spaces2)

        logger.info(s"Traffic & Activity Records results written to $savePath")
      }
    }
  }

  private def getHoldings(partyId: PartyId, hostedOnValidator: ValidatorAppBackendReference) = {
    hostedOnValidator.participantClientWithAdminToken.ledger_api.state.acs
      .of_party(
        party = partyId,
        filterInterfaces = Seq(holdingv2.Holding.TEMPLATE_ID).map(templateId =>
          TemplateId(
            templateId.getPackageId,
            templateId.getModuleName,
            templateId.getEntityName,
          )
        ),
        includeCreatedEventBlob = true,
      )
  }

  private def getUsdcBalance(partyId: PartyId, hostedOnValidator: ValidatorAppBackendReference) = {
    getHoldings(partyId, hostedOnValidator)
      .map { entry =>
        Contract
          .fromCreatedEvent(holdingv2.Holding.INTERFACE)(
            CreatedEvent.fromProto(
              createdEventToJavaProto(
                entry.event
              )
            )
          )
          .valueOrFail("Failed to read Holding")
      }
      .filter(_.payload.instrumentId.id == usdcInstrumentName)
      .map(_.payload.amount)
      .map(BigDecimal(_))
      .sum
  }

  class TestTokenV2Registry(
      adminParty: PartyId,
      adminValidatorBackend: ValidatorAppBackendReference,
  ) {
    private def getTokenRules() = {
      val fromLedger = adminValidatorBackend.participantClient.ledger_api.state.acs
        .of_party(
          party = adminParty,
          filterTemplates = Seq(TokenV2Rules.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
          includeCreatedEventBlob = true,
        )
        .loneElement
      Contract
        .fromCreatedEvent(TokenV2Rules.COMPANION)(
          CreatedEvent.fromProto(
            createdEventToJavaProto(
              fromLedger.event
            )
          )
        )
        .valueOrFail("Failed to read TokenV2Rules") -> fromLedger.synchronizerId.valueOrFail(
        "No synchronizerid defined"
      )
    }

    def getContext(
        inputHoldingCids: Seq[holdingv2.Holding.ContractId]
    ): ChoiceContextWithDisclosures = {
      val (tokenRules, synchronizerId) = getTokenRules()
      val holdings = inputHoldingCids.map { holdingCid =>
        val holding = adminValidatorBackend.participantClient.ledger_api.state.acs
          .of_party(
            party = adminParty,
            filterInterfaces = Seq(holdingv2.Holding.TEMPLATE_ID).map(templateId =>
              TemplateId(
                templateId.getPackageId,
                templateId.getModuleName,
                templateId.getEntityName,
              )
            ),
            includeCreatedEventBlob = true,
          )
          .find(_.contractId == holdingCid.contractId)
          .valueOrFail(s"No Holding for cid $holdingCid")
        CommandsOuterClass.DisclosedContract
          .newBuilder()
          .setContractId(holdingCid.contractId)
          .setCreatedEventBlob(holding.event.createdEventBlob)
          .setSynchronizerId(synchronizerId.toProtoPrimitive)
          .setTemplateId(identifierToJavaProto(holding.templateId.toIdentifier))
          .build()
      }

      val disclosedContracts = holdings :+ CommandsOuterClass.DisclosedContract
        .newBuilder()
        .setContractId(tokenRules.contractId.contractId)
        .setCreatedEventBlob(tokenRules.createdEventBlob)
        .setSynchronizerId(synchronizerId.toProtoPrimitive)
        .setTemplateId(tokenRules.identifier.toProto)
        .build()
      ChoiceContextWithDisclosures(
        disclosedContracts,
        new metadatav1.ChoiceContext(
          java.util.Map.of(
            "testTokenV2/tokenRules",
            new AV_ContractId(
              new metadatav1.AnyContract.ContractId(tokenRules.contractId.contractId)
            ),
          )
        ),
      )
    }
  }
}
