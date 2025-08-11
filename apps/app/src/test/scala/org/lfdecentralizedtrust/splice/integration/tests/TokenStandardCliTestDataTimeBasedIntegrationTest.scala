package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.event_query_service
import com.daml.ledger.api.v2.state_service.GetActiveContractsRequest
import com.daml.ledger.api.v2.update_service.GetUpdatesRequest
import com.daml.ledger.api.v2.transaction_filter.CumulativeFilter.IdentifierFilter
import com.daml.ledger.api.v2.transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS
import com.daml.ledger.api.v2.transaction_filter.{
  CumulativeFilter,
  EventFormat,
  Filters,
  InterfaceFilter,
  TransactionFilter,
  TransactionFormat,
  UpdateFormat,
  WildcardFilter,
}
import com.daml.ledger.javaapi.data.Identifier
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.http.json.v2.JsSchema.JsEvent
import com.digitalasset.canton.http.json.v2.{
  JsContractEntry,
  JsEventServiceCodecs,
  JsGetActiveContractsResponse,
  JsGetUpdatesResponse,
  JsStateServiceCodecs,
  JsUpdate,
  JsUpdateServiceCodecs,
}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.google.protobuf
import com.google.protobuf.ByteString
import io.circe.{Decoder, Encoder, Json}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.Post
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.test.dummyholding.DummyHolding
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

// Not checking Daml compatibility as the test data can be specific to the most recent
// version of the Daml code, and thus the test would falsely fail when running with older
// Daml code versions.
@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class TokenStandardCliTestDataTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with TokenStandardTest
    with WalletTestUtil
    with HasActorSystem
    with TimeTestUtil
    with HasExecutionContext {

  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreForTemplateIds(
      Seq(DummyHolding.TEMPLATE_ID)
    )

  private val dummyHoldingDarPath = Paths
    .get(
      "token-standard/examples/splice-token-test-dummy-holding/.daml/dist/splice-token-test-dummy-holding-current.dar"
    )
    .toAbsolutePath
    .toString

  private val isCI = sys.env.contains("CI")

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      // Needs to be simtime for amounts to be consistent: `computeTransferPreapprovalFee` depends on current time
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging // we need a deterministic amount of amulets
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClientWithAdminToken
          .upload_dar_unless_exists(dummyHoldingDarPath)
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
  }

  private val interfaces: Seq[Identifier] =
    Seq(
      transferinstructionv1.TransferFactory.TEMPLATE_ID,
      transferinstructionv1.TransferInstruction.TEMPLATE_ID,
      holdingv1.Holding.TEMPLATE_ID,
    )

  val testDataPath = "token-standard/cli/__tests__/mocks/data/"
  def replaceOrFail[R](targetFile: String, normalizedContent: R, encoder: Encoder[R]): Unit = {
    val path = Paths.get(testDataPath, targetFile)
    val prettyNormalizedContent = encoder(normalizedContent).spaces2SortKeys
    if (isCI) {
      val actual = Files.readString(path)

      // Writing file for debugging
      val debugPath = Paths.get("log", s"token-standard-cli-actual-test-data-$targetFile")
      Files.writeString(debugPath, prettyNormalizedContent)
      if (actual != prettyNormalizedContent) {
        fail(
          "Test data is not up-to-date. Please run the test locally to update it. " +
            s"You can find the returned test data in $debugPath."
        )
      }
      actual should be(prettyNormalizedContent)
    } else {
      Files.writeString(path, prettyNormalizedContent)
    }
  }

  "Token Standard CLI" should {

    "have up-to-date test data" in { implicit env =>
      advanceTime(java.time.Duration.ofMinutes(1L))

      // Onboard and Create/Accept ExternalPartySetupProposal for Bob
      val (bob, _) = actAndCheck(
        "Onboard bob as an external party on bobValidator", {
          // Ensure bob's validator has enough funds to onboard bob
          bobValidatorWalletClient.tap(100)
          val onboardingBob = onboardExternalParty(bobValidatorBackend, Some("bobExternal"))
          clue("Create and accept onboarding proposal for bob") {
            val proposalBob = createExternalPartySetupProposal(bobValidatorBackend, onboardingBob)
            val preparePartySetupBob =
              prepareAcceptExternalPartySetupProposal(
                bobValidatorBackend,
                onboardingBob,
                proposalBob,
              )
            submitExternalPartySetupProposal(
              bobValidatorBackend,
              onboardingBob,
              preparePartySetupBob,
            )
            onboardingBob.richPartyId
          }
        },
      )(
        "Bob's preapproval is visible",
        bob => {
          bobValidatorBackend.lookupTransferPreapprovalByParty(bob.partyId) should not be empty
          bobValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(
            bob.partyId
          ) should not be empty
        },
      )

      // Check that Bob's balance is empty
      def getBobPartyBalance(): HttpWalletAppClient.Balance = {
        val extBalance = bobValidatorBackend.getExternalPartyBalance(bob.partyId)
        HttpWalletAppClient.Balance(
          round = extBalance.computedAsOfRound,
          unlockedQty = BigDecimal(extBalance.totalUnlockedCoin),
          lockedQty = BigDecimal(extBalance.totalLockedCoin),
          holdingFees = BigDecimal(extBalance.accumulatedHoldingFeesTotal),
        )
      }
      getBobPartyBalance().unlockedQty shouldBe BigDecimal("0.0")

      actAndCheck(
        "Cancel Bob's pre-approval, as we want to test it with two-step transfers",
        bobValidatorBackend.cancelTransferPreapprovalByParty(bob.partyId),
      )(
        "Bob's preapproval is no longer visible",
        _ => bobValidatorBackend.lookupTransferPreapprovalByParty(bob.partyId) shouldBe empty,
      )

      // Onboard alice as a local party
      val alice = RichPartyId.local(onboardWalletUser(aliceWalletClient, aliceValidatorBackend))
      val aliceValidator = RichPartyId.local(aliceValidatorBackend.getValidatorPartyId())

      aliceValidatorWalletClient.tap(BigDecimal(1000))
      aliceWalletClient.createTransferPreapproval()
      aliceValidatorWalletClient.createTransferPreapproval()

      logger.info(
        s"Generating CLI data for" +
          s" alice: ${alice.partyId.toProtoPrimitive}" +
          s" bob: ${bob.partyId.toProtoPrimitive}" +
          s" validator: ${aliceValidatorBackend.getValidatorPartyId().toProtoPrimitive}"
      )

      val (_, (activeHoldingsResponse, activeTransferInstructionsResponse, getUpdatesResponse)) =
        actAndCheck(
          "Create some holdings", {
            // Amulet holdings: one transferred via token standard, one tapped (minted)
            // TransferIn
            executeTransferViaTokenStandard(
              aliceValidatorBackend.participantClientWithAdminToken,
              aliceValidator,
              alice.partyId,
              BigDecimal("200.0"),
              transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct,
              description = Some("token-standard-transfer-description"),
            )
            eventually() {
              aliceWalletClient.balance().unlockedQty should beAround(BigDecimal("200"))
              val scanTxs = sv1ScanBackend.listActivity(None, 1000)
              forExactly(1, scanTxs) { tx =>
                val transfer = tx.transfer.value
                transfer.transferKind shouldBe Some(
                  definitions.Transfer.TransferKind.members.PreapprovalSend
                )
                transfer.description shouldBe Some("token-standard-transfer-description")
                transfer.sender.party shouldBe aliceValidator.partyId.toProtoPrimitive
                transfer.receivers.loneElement.party shouldBe alice.partyId.toProtoPrimitive
              }
            }
            // send some back so there's a TransferOut
            executeTransferViaTokenStandard(
              aliceValidatorBackend.participantClientWithAdminToken,
              alice,
              aliceValidator.partyId,
              BigDecimal("100.0"),
              transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct,
            )
            eventually() {
              aliceWalletClient.balance().unlockedQty should beAround(BigDecimal("87")) // fees!
            }
            // Deliberately using a non-token-standard transfer so that this shows up as a Transfer still (tx-kind-based)
            aliceWalletClient.transferPreapprovalSend(
              aliceValidator.partyId,
              BigDecimal("10.0"),
              UUID.randomUUID().toString,
            )
            eventually() {
              aliceWalletClient.balance().unlockedQty should beAround(BigDecimal("64.9")) // fees!
            }
            aliceWalletClient.tap(walletAmuletToUsd(6000.0))
            val (_, bobInstructionCids) = actAndCheck(
              "Alice creates 5 transfers to Bob who has no pre-approval", {
                for (_ <- 1 to 4) {
                  executeTransferViaTokenStandard(
                    aliceValidatorBackend.participantClientWithAdminToken,
                    alice,
                    bob.partyId,
                    BigDecimal("1000.0"),
                    transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
                    timeToLife = java.time.Duration.ofMinutes(1),
                  )
                }
                // the fifth one uses a longer duration so that it does not expire later when passing time
                executeTransferViaTokenStandard(
                  aliceValidatorBackend.participantClientWithAdminToken,
                  alice,
                  bob.partyId,
                  BigDecimal("1000.0"),
                  transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
                  timeToLife = java.time.Duration.ofMinutes(20),
                )
              },
            )(
              "Bob can see the transfer instructions",
              _ => {
                val instructions = listTransferInstructions(
                  bobValidatorBackend.participantClientWithAdminToken,
                  bob.partyId,
                )
                instructions should have size 5
                instructions.map(_._1)
              },
            )
            actAndCheck(
              "Bob accepts transfer instruction #1", {
                val now = env.environment.clock.now
                acceptTransferInstruction(
                  bobValidatorBackend.participantClientWithAdminToken,
                  bob,
                  bobInstructionCids(0),
                  // we advanced time by 2min since the transfer instruction and it had a lifetime of 1min
                  // so the lower bound is 1min in the past.
                  expectedTimeBounds =
                    Some((now.minusSeconds(60), now.plusSeconds(60).addMicros(-1))),
                )
              },
            )(
              "Bob sees the funds",
              _ =>
                getBobPartyBalance().unlockedQty should beAround(
                  BigDecimal("1000.0")
                ),
            )
            actAndCheck(
              "Bob rejects transfer instruction #2 and Alice withdraws #3", {
                rejectTransferInstruction(
                  bobValidatorBackend.participantClientWithAdminToken,
                  bob,
                  bobInstructionCids(1),
                  expectedTimeBounds = Some((CantonTimestamp.MinValue, CantonTimestamp.MaxValue)),
                )
                withdrawTransferInstruction(
                  aliceValidatorBackend.participantClientWithAdminToken,
                  alice,
                  bobInstructionCids(2),
                )
              },
            )(
              "Bob sees only two remaining transfer instructions, and funds are as expected",
              _ => {
                val instructions = listTransferInstructions(
                  bobValidatorBackend.participantClientWithAdminToken,
                  bob.partyId,
                )
                instructions should have size 2
                getBobPartyBalance().unlockedQty should beAround(BigDecimal("1000.0"))
                inside(aliceWalletClient.balance()) { aliceBalance =>
                  aliceBalance.unlockedQty should beWithin(
                    BigDecimal("2800.0"),
                    BigDecimal("3000.0"),
                  )
                  aliceBalance.lockedQty should beWithin(BigDecimal("2000.0"), BigDecimal("2150.0"))
                }
              },
            )
            clue("Test receiving two-step transfers") {
              actAndCheck(
                "Alice validator cancels TransferPreapproval for alice",
                aliceValidatorBackend.cancelTransferPreapprovalByParty(alice.partyId),
              )(
                "See that TransferPreapproval for alice has been cancelled",
                _ => {
                  aliceValidatorBackend.lookupTransferPreapprovalByParty(
                    alice.partyId
                  ) shouldBe None
                  sv1ScanBackend.lookupTransferPreapprovalByParty(alice.partyId) shouldBe None
                },
              )
              val (_, aliceInstructionCids) = actAndCheck(
                "Bob creates 4 transfers to Alice who has no longer a pre-approval",
                for (_ <- 1 to 4) {
                  val now = env.environment.clock.now
                  executeTransferViaTokenStandard(
                    bobValidatorBackend.participantClientWithAdminToken,
                    bob,
                    alice.partyId,
                    BigDecimal("200.0"),
                    transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
                    expectedTimeBounds = Some((now, now.plusSeconds(10 * 60).addMicros(-1))),
                  )
                },
              )(
                "Alice can see the transfer instructions",
                _ => {
                  val instructions = listTransferInstructions(
                    aliceValidatorBackend.participantClientWithAdminToken,
                    alice.partyId,
                  )
                  instructions should have size 6 // two outstanding ones from alice to bob, and four new ones from bob to alice
                  instructions.collect {
                    case instr if instr._2.transfer.receiver == alice.partyId.toProtoPrimitive =>
                      instr._1
                  }
                },
              )
              actAndCheck(
                "Alice accepts instruction #1 and rejects #2; and bob withdraws #3; while #4 is left as-is", {
                  clue("Accept instruction #1") {
                    acceptTransferInstruction(
                      aliceValidatorBackend.participantClientWithAdminToken,
                      alice,
                      aliceInstructionCids(0),
                    )
                  }
                  clue("Reject instruction #2") {
                    rejectTransferInstruction(
                      aliceValidatorBackend.participantClientWithAdminToken,
                      alice,
                      aliceInstructionCids(1),
                    )
                  }
                  clue("Withdraw instruction #3") {
                    withdrawTransferInstruction(
                      bobValidatorBackend.participantClientWithAdminToken,
                      bob,
                      aliceInstructionCids(2),
                      expectedTimeBounds =
                        Some((CantonTimestamp.MinValue, CantonTimestamp.MaxValue)),
                    )
                  }
                },
              )(
                "There are three open transfer instructions left and bob observers their updated holdings",
                _ => {
                  val instructions = listTransferInstructions(
                    aliceValidatorBackend.participantClientWithAdminToken,
                    alice.partyId,
                  )
                  instructions should have size 3
                  val bobHoldings = listHoldings(
                    bobValidatorBackend.participantClientWithAdminToken,
                    bob.partyId,
                  )
                  val (unlockedHoldings, lockedHoldings) =
                    bobHoldings.partition(h => h._2.lock.isEmpty)
                  unlockedHoldings should have size 4
                  lockedHoldings should have size 1
                },
              )
            }

            // Test self-transfer for party w/o pre-approval
            actAndCheck(
              "Bob splits his holdings into two using a self-transfer", {
                val now = env.environment.clock.now
                executeTransferViaTokenStandard(
                  bobValidatorBackend.participantClientWithAdminToken,
                  bob,
                  bob.partyId,
                  BigDecimal("250.0"),
                  transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Self,
                  expectedTimeBounds = Some((now, now.plusSeconds(10 * 60).addMicros(-1))),
                )
              },
            )(
              // 1 locked holding, untouched by the transfer. two unlocked holdings as the output of the self transfer.
              "Bob's has three holdings and the balance remained the same (modulo fees)",
              _ => {
                listHoldings(
                  bobValidatorBackend.participantClientWithAdminToken,
                  bob.partyId,
                ) should have size 3
                inside(getBobPartyBalance()) { balance =>
                  balance.unlockedQty should beWithin(
                    BigDecimal("450.0"),
                    BigDecimal("600.0"),
                  ) // received 1_000, transferred back 200, and 200 in-flight
                  balance.lockedQty should beWithin(
                    BigDecimal("200.0"),
                    BigDecimal("270.0"),
                  ) // locked amount + fee reserve
                }
              },
            )
            // Advance by two minutes so that the short-lived 1 minute transfer instruction from Alice expires
            val instrAboutToExpire = bobInstructionCids(3)
            advanceTime(java.time.Duration.ofMinutes(2))
            clue("Alice as two locked holdings") {
              inside(
                listHoldings(
                  aliceValidatorBackend.participantClientWithAdminToken,
                  alice.partyId,
                )
              ) { holdings =>
                holdings.count { case (_, holding) => holding.lock.isPresent } shouldBe 2
              }
            }
            // Test self-transfer for wallet history
            actAndCheck(
              "Alice merges her four unlocked holdings, and the expired locked amulet into a two using a self-transfer",
              executeTransferViaTokenStandard(
                aliceValidatorBackend.participantClientWithAdminToken,
                alice,
                alice.partyId,
                BigDecimal("1.0"),
                transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Self,
              ),
            )(
              "Alice's has three holdings and the balance remained the same (modulo fees)",
              _ => {
                listHoldings(
                  aliceValidatorBackend.participantClientWithAdminToken,
                  alice.partyId,
                ) should have size 3
                inside(aliceWalletClient.balance()) { aliceBalance =>
                  aliceBalance.unlockedQty should beWithin(
                    BigDecimal("4100.0"),
                    BigDecimal("4200.0"),
                  )
                  aliceBalance.lockedQty should beWithin(BigDecimal("1000.0"), BigDecimal("1100.0"))
                }
                // there is one less locke
              },
            )
            clue("Alice as one locked holding left") {
              inside(
                listHoldings(
                  aliceValidatorBackend.participantClientWithAdminToken,
                  alice.partyId,
                )
              ) { holdings =>
                holdings.count { case (_, holding) => holding.lock.isPresent } shouldBe 1
              }
            }
            actAndCheck(
              "Bob rejects instruction #4, whose backing amulet has already been archived", {
                val now = env.environment.clock.now
                rejectTransferInstruction(
                  bobValidatorBackend.participantClientWithAdminToken,
                  bob,
                  instrAboutToExpire,
                  // we advanced time by 2min since the transfer instruction and it had a lifetime of 1min
                  // so the lower bound is 1min in the past.
                  expectedTimeBounds = Some((now.minusSeconds(60), CantonTimestamp.MaxValue)),
                )
              },
            )(
              "There are two open transfer instructions left",
              _ => {
                val instructions = listTransferInstructions(
                  aliceValidatorBackend.participantClientWithAdminToken,
                  alice.partyId,
                )
                instructions should have size 2
              },
            )

            // DummyHolding holdings
            Seq("30.0", "40.0").foreach { amount =>
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitJavaExternalOrLocal(
                  alice,
                  commands = new DummyHolding(
                    alice.partyId.toProtoPrimitive,
                    alice.partyId.toProtoPrimitive,
                    BigDecimal(amount).bigDecimal,
                  )
                    .create()
                    .commands()
                    .asScala
                    .toSeq,
                )
            }
          },
        )(
          "holdings and transactions are returned",
          _ => {
            val activeHoldingsResponse =
              listContractsOfInterface(alice, holdingv1.Holding.TEMPLATE_ID)

            activeHoldingsResponse should have size 5 // 2 unlocked amulets, 1 locked amulet, 2 sample holdings

            val activeTransferInstructionsResponse =
              listContractsOfInterface(bob, transferinstructionv1.TransferInstruction.TEMPLATE_ID)

            activeTransferInstructionsResponse should have size 2

            val getUpdatesPayload = JsUpdateServiceCodecs.getUpdatesRequest(
              GetUpdatesRequest(
                updateFormat = Some(
                  UpdateFormat(includeTransactions =
                    Some(
                      TransactionFormat(
                        transactionShape = TRANSACTION_SHAPE_LEDGER_EFFECTS,
                        eventFormat = Some(
                          EventFormat(
                            filtersByParty(alice.partyId, interfaces, includeWildcard = true)
                          )
                        ),
                      )
                    )
                  )
                )
              )
            )

            val getUpdatesResponse = makeJsonApiV2Request(
              "/v2/updates/flats",
              getUpdatesPayload,
              io.circe.Decoder.decodeSeq(JsUpdateServiceCodecs.jsGetUpdatesResponse),
            )

            (activeHoldingsResponse, activeTransferInstructionsResponse, getUpdatesResponse)
          },
        )

      def mkTimestamp(major: Long, minor: Int) = {
        // move to a date that's far away from 1970, so we can grep for
        // un-normalized timestamps
        protobuf.timestamp.Timestamp.of(365 * 24 * 3600 + major, 10_000 * minor)
      }

      val replaceTemplateIdR = "^[^:]+".r
      def stableTemplateId(templateId: String) = {
        replaceTemplateIdR.replaceFirstIn(templateId, "#package-name")
      }

      // Only works under the assumption that they always appear in the same order
      val contractIds = mutable.ArrayBuffer[String]()
      def replaceContractIdWithStableString(contractId: String): Int = {
        val idx = contractIds.indexOf(contractId)
        if (idx == -1) {
          contractIds.append(contractId)
          contractIds.length - 1
        } else idx
      }

      val expectedParties = Map(
        alice.partyId.toProtoPrimitive -> "alice::normalized",
        dsoParty.toProtoPrimitive -> "dso::normalized",
        aliceValidatorBackend
          .getValidatorPartyId()
          .toProtoPrimitive -> "aliceValidator::normalized",
        bob.partyId.toProtoPrimitive -> "bob::normalized",
      )
      val dateFields =
        Seq(
          "createdAt",
          "expiresAt",
          "lastRenewedAt",
          "validFrom",
          "requestedAt",
          "executeBefore",
          "opensAt",
          "targetClosesAt",
          "value", // due to BurnMint using `AV_Time` for the expiresAt field of the locked output
        )
      def replaceStringsInJson(viewValue: Json) = {
        val current = viewValue.spaces2SortKeys
        val amuletRulesId = sv1ScanBackend.getAmuletRules().contractId.contractId
        val allContracts =
          "\"([0-9a-fA-F]{138})\"".r.findAllIn(current).matchData.map(_.group(1)).toSeq

        val stableContractIdsAndParties =
          (expectedParties.toSeq ++ allContracts.map(cid =>
            cid -> replaceContractIdWithStableString(cid).toString
          ))
            .foldLeft(current) { case (acc, (party, normalized)) =>
              acc.replace(party, normalized)
            }
            .replace(aliceWalletClient.config.ledgerApiUser, "the_user")
            .replace(
              amuletRulesId,
              replaceContractIdWithStableString(
                amuletRulesId
              ).toString,
            )

        val stableDates = dateFields.foldLeft(stableContractIdsAndParties) {
          case (acc, fieldToReplace) =>
            acc.replaceAll(
              s"\"${fieldToReplace}\" : \"(\\d{4}-\\d{2}-\\d{2}T.*Z)\"",
              s"\"${fieldToReplace}\" : \"2025-06-18T00:00:00.000000Z\"",
            )
        }

        io.circe.parser
          .parse(stableDates)
          .valueOrFail("expected valid json after normalization")
      }

      def normalizeCreatedEvent(
          created: JsEvent.CreatedEvent,
          offset: Long,
          createdAt: protobuf.timestamp.Timestamp,
      ) = {
        created.copy(
          offset = offset,
          contractId = replaceContractIdWithStableString(created.contractId).toString,
          templateId = stableTemplateId(created.templateId),
          createArgument = created.createArgument.map(replaceStringsInJson),
          createdEventBlob = ByteString.empty(),
          interfaceViews = created.interfaceViews.map(view =>
            view.copy(
              viewValue = Some(
                replaceStringsInJson(
                  view.viewValue.valueOrFail("Expected view value to be available.")
                )
              ),
              interfaceId = stableTemplateId(view.interfaceId),
            )
          ),
          witnessParties = created.witnessParties.map(expectedParties),
          signatories = created.signatories.map(expectedParties),
          observers = created.observers.map(expectedParties),
          createdAt = createdAt,
        )
      }

      def normalizeArchivedEvent(archived: JsEvent.ArchivedEvent, offset: Long) = {
        archived.copy(
          offset = offset,
          contractId = replaceContractIdWithStableString(archived.contractId).toString,
          templateId = stableTemplateId(archived.templateId),
          witnessParties = archived.witnessParties.map(expectedParties),
          implementedInterfaces = archived.implementedInterfaces.map(stableTemplateId),
        )
      }

      def normalizeActiveContractsResponse(
          activeContractsResponse: Seq[JsGetActiveContractsResponse]
      ) = activeContractsResponse.map(entry =>
        entry.copy(contractEntry = entry.contractEntry match {
          case active: JsContractEntry.JsActiveContract =>
            active.copy(
              createdEvent = active.createdEvent.copy(
                contractId =
                  replaceContractIdWithStableString(active.createdEvent.contractId).toString,
                templateId = stableTemplateId(active.createdEvent.templateId),
                createdAt = mkTimestamp(0, 0),
                createdEventBlob = ByteString.empty(),
                interfaceViews = active.createdEvent.interfaceViews.map(iv =>
                  iv.copy(
                    viewValue = Some(
                      replaceStringsInJson(
                        iv.viewValue.valueOrFail("Expected view value to be available.")
                      )
                    ),
                    interfaceId = stableTemplateId(iv.interfaceId),
                  )
                ),
                nodeId = 1,
                offset = 1,
                signatories = active.createdEvent.signatories.map(expectedParties),
                observers = active.createdEvent.observers.map(expectedParties),
                witnessParties = active.createdEvent.witnessParties.map(expectedParties),
              ),
              synchronizerId = "sync::normalized",
            )
          case _ =>
            throw new IllegalStateException(
              s"Should be active contract but it's not? $activeContractsResponse"
            )
        })
      )

      val normalizedHoldings = normalizeActiveContractsResponse(activeHoldingsResponse)
      replaceOrFail(
        "holdings.json",
        normalizedHoldings,
        io.circe.Encoder
          .encodeSeq(JsStateServiceCodecs.jsGetActiveContractsResponseRW),
      )

      val normalizedUpdates = getUpdatesResponse
        .map(_.update)
        .collect {
          // OffsetCheckpoint are inconsistent as to where they appear in the stream
          // Reassignment & TopologyTransaction shouldn't appear
          case tx: JsUpdate.Transaction =>
            tx
        }
        .zipWithIndex
        .map { case (transaction, idx) =>
          JsUpdate.Transaction(
            transaction.value.copy(
              updateId = s"update-$idx",
              commandId = s"command-$idx",
              effectiveAt = mkTimestamp(idx.toLong, 0),
              offset = idx.toLong,
              synchronizerId = "sync::normalized",
              traceContext = None,
              recordTime = mkTimestamp(idx.toLong, 0),
              events = transaction.value.events.zipWithIndex.map {
                case (created: JsEvent.CreatedEvent, eventIdx) =>
                  normalizeCreatedEvent(
                    created,
                    idx.toLong,
                    mkTimestamp(idx.toLong, eventIdx),
                  )
                case (archived: JsEvent.ArchivedEvent, _) =>
                  normalizeArchivedEvent(archived, idx.toLong)
                case (exercised: JsEvent.ExercisedEvent, _) =>
                  exercised.copy(
                    offset = idx.toLong,
                    contractId = replaceContractIdWithStableString(exercised.contractId).toString,
                    templateId = exercised.templateId.copy(packageId = "#package-name"),
                    choiceArgument = replaceStringsInJson(exercised.choiceArgument),
                    actingParties = exercised.actingParties.map(expectedParties),
                    witnessParties = exercised.witnessParties.map(expectedParties),
                    exerciseResult = replaceStringsInJson(exercised.exerciseResult),
                    interfaceId = exercised.interfaceId.map(_.copy(packageId = "#package-name")),
                    implementedInterfaces =
                      exercised.implementedInterfaces.map(_.copy(packageId = "#package-name")),
                  )
              },
            )
          )
        }

      replaceOrFail(
        "txs.json",
        normalizedUpdates.map(JsGetUpdatesResponse(_)),
        io.circe.Encoder.encodeSeq(JsUpdateServiceCodecs.jsGetUpdatesResponse),
      )

      val contractIdsAtStart = contractIds.toVector
      val eventByIdResponses = contractIdsAtStart.map { cid =>
        tryMakeJsonApiV2Request(
          "/v2/events/events-by-contract-id",
          JsEventServiceCodecs.getEventsByContractIdRequestRW(
            event_query_service.GetEventsByContractIdRequest(
              cid,
              Seq.empty,
              Some(
                EventFormat(
                  filtersByParty(
                    alice.partyId,
                    Seq(
                      holdingv1.Holding.TEMPLATE_ID,
                      transferinstructionv1.TransferInstruction.TEMPLATE_ID,
                    ),
                    includeWildcard = true,
                  )
                )
              ),
            )
          ),
          JsEventServiceCodecs.jsGetEventsByContractIdResponseRW,
        ) match {
          case Left(err) =>
            logger.debug(s"FAILED: /v2/events/events-by-contract-id/$cid\n$err")
            None
          case Right(getEventsByContractIdResponse) =>
            Some(
              getEventsByContractIdResponse.copy(
                created = getEventsByContractIdResponse.created.map(created =>
                  created.copy(
                    createdEvent = normalizeCreatedEvent(
                      created.createdEvent,
                      42L,
                      mkTimestamp(42L, 42),
                    ),
                    synchronizerId = "sync::normalized",
                  )
                ),
                archived = getEventsByContractIdResponse.archived.map(archived =>
                  archived.copy(
                    archivedEvent = normalizeArchivedEvent(archived.archivedEvent, 42L),
                    synchronizerId = "sync::normalized",
                  )
                ),
              )
            )
        }
      }
      replaceOrFail(
        s"eventsByContractIdResponses.json",
        eventByIdResponses.flatten,
        io.circe.Encoder.encodeSeq(JsEventServiceCodecs.jsGetEventsByContractIdResponseRW),
      )

      val normalizedTransferInstructions =
        normalizeActiveContractsResponse(activeTransferInstructionsResponse)
      replaceOrFail(
        "transfer-instructions.json",
        normalizedTransferInstructions,
        io.circe.Encoder
          .encodeSeq(JsStateServiceCodecs.jsGetActiveContractsResponseRW),
      )
    }
  }

  private val jsonApiPort = 16501
  private def makeJsonApiV2Request[R](subPath: String, payload: Json, decode: Decoder[R])(implicit
      env: SpliceTestConsoleEnvironment
  ): R =
    tryMakeJsonApiV2Request(subPath, payload, decode).value

  private def tryMakeJsonApiV2Request[R](subPath: String, payload: Json, decode: Decoder[R])(
      implicit env: SpliceTestConsoleEnvironment
  ): Either[String, R] = {
    val response = Http()
      .singleRequest(
        Post(
          s"http://localhost:${jsonApiPort}${subPath}",
          payload.noSpaces,
        ).withHeaders(
          Seq(
            RawHeader(
              "Authorization",
              s"Bearer ${aliceValidatorBackend.participantClientWithAdminToken.adminToken.value}",
            )
          )
        )
      )
      .futureValue
    val rawResponse = Unmarshal(response).to[String].futureValue
    if (response.status.isFailure()) {
      Left(s"Failed to execute request to $subPath: $rawResponse")
    } else {
      io.circe.parser
        .parse(rawResponse)
        .valueOrFail("failed to parse json")
        .as[R](decode)
        .fold(fa => Left(s"Failed to decode $rawResponse: $fa"), Right(_))
    }
  }

  private def listContractsOfInterface(party: RichPartyId, interface: Identifier)(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[JsGetActiveContractsResponse] = {
    val getActiveContractsPayload = JsStateServiceCodecs.getActiveContractsRequestRW(
      GetActiveContractsRequest(
        filter = Some(
          TransactionFilter(
            filtersByParty(
              party.partyId,
              Seq(interface),
              includeWildcard = false,
            )
          )
        ),
        activeAtOffset =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.end(),
      )
    )

    makeJsonApiV2Request(
      "/v2/state/active-contracts",
      getActiveContractsPayload,
      io.circe.Decoder.decodeSeq(JsStateServiceCodecs.jsGetActiveContractsResponseRW),
    )
  }

  private def filtersByParty(
      party: PartyId,
      interfaces: Seq[Identifier],
      includeWildcard: Boolean,
  ) = Map(
    party.toProtoPrimitive -> Filters(
      interfaces.map(interface =>
        CumulativeFilter(
          IdentifierFilter.InterfaceFilter(
            InterfaceFilter(
              Some(
                TemplateId
                  .fromJavaIdentifier(interface)
                  .toIdentifier
              ),
              includeCreatedEventBlob = true,
              includeInterfaceView = true,
            )
          )
        )
      ) ++ (if (includeWildcard)
              Seq(
                CumulativeFilter(
                  IdentifierFilter.WildcardFilter(
                    WildcardFilter(true)
                  )
                )
              )
            else Seq.empty)
    )
  )

}
