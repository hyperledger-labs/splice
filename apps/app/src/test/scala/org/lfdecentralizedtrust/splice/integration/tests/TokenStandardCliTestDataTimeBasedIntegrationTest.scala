package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.commands.Command.Command.Create
import com.daml.ledger.api.v2.commands.{Command, CreateCommand}
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
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.http.json.v2.JsSchema.JsEvent
import com.digitalasset.canton.http.json.v2.{
  JsContractEntry,
  JsGetUpdatesResponse,
  JsStateServiceCodecs,
  JsUpdate,
  JsUpdateServiceCodecs,
}
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
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger

import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class TokenStandardCliTestDataTimeBasedIntegrationTest
    extends TokenStandardTest
    with WalletTestUtil
    with HasActorSystem
    with TimeTestUtil
    with HasExecutionContext {

  private val sampleHoldingDarPath = Paths
    .get(
      "token-standard/examples/splice-token-test-dummy-holding/.daml/dist/splice-api-token-sample-holding-current.dar"
    )
    .toAbsolutePath
    .toString

  private val isCI = sys.env.contains("CI")

  // TODO (#17384): support token standard choices in the script
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      // Needs to be simtime for amounts to be consistent: `computeTransferPreapprovalFee` depends on current time
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging // we need a deterministic amount of amulets
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClientWithAdminToken
          .upload_dar_unless_exists(sampleHoldingDarPath)
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
  }

  def replaceOrFail[R](targetPath: String, normalizedContent: R, encoder: Encoder[R]): Unit = {
    val path = Paths.get(targetPath)
    val prettyNormalizedContent = encoder(normalizedContent).spaces2SortKeys
    if (isCI) {
      val actual = Files.readString(path)

      actual should be(prettyNormalizedContent)
    } else {
      Files.writeString(path, prettyNormalizedContent)
    }
  }

  "Token Standard CLI" should {

    "have up-to-date test data" in { implicit env =>
      advanceTime(java.time.Duration.ofMinutes(1L))

      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceValidatorWalletClient.tap(BigDecimal(1000))
      aliceWalletClient.createTransferPreapproval()
      aliceValidatorWalletClient.createTransferPreapproval()

      val (_, (activeContractsResponse, getUpdatesResponse)) = actAndCheck(
        "Create some holdings", {
          // Amulet holdings: one transferred via token standard, one tapped (minted)
          executeTransferViaTokenStandard(
            aliceValidatorBackend.participantClientWithAdminToken,
            aliceValidatorBackend.getValidatorPartyId(),
            alice,
            BigDecimal("200.0"),
          )
          eventually() {
            aliceWalletClient.balance().unlockedQty should beAround(BigDecimal("200"))
          }
          // send some back so there's a TransferFactory_Transfer node in the other direction
          executeTransferViaTokenStandard(
            aliceValidatorBackend.participantClientWithAdminToken,
            alice,
            aliceValidatorBackend.getValidatorPartyId(),
            BigDecimal("100.0"),
          )
          eventually() {
            aliceWalletClient.balance().unlockedQty should beAround(BigDecimal("87")) // fees!
          }
          aliceWalletClient.tap(BigDecimal("100.0"))

          // SampleHolding holdings
          Seq("30.0", "40.0").foreach { amount =>
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.commands
              .submit(
                actAs = Seq(alice),
                readAs = Seq(alice),
                optTimeout = None,
                commands = Seq(
                  Command(
                    Create(
                      CreateCommand.fromJavaProto(
                        new DummyHolding(
                          alice.toProtoPrimitive,
                          alice.toProtoPrimitive,
                          BigDecimal(amount).bigDecimal,
                        )
                          .create()
                          .commands()
                          .asScala
                          .toSeq
                          .head
                          .toProtoCommand
                          .getCreate
                      )
                    )
                  )
                ),
              )
          }
        },
      )(
        "holdings and transactions are returned",
        _ => {
          val interfaces =
            Seq(
              transferinstructionv1.TransferFactory.TEMPLATE_ID,
              holdingv1.Holding.TEMPLATE_ID,
              holdingv1.BurnMintFactory.TEMPLATE_ID,
            )
          def filtersByParty(includeWildcard: Boolean) = Map(
            alice.toProtoPrimitive -> Filters(
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

          val getActiveContractsPayload = JsStateServiceCodecs.getActiveContractsRequestRW(
            GetActiveContractsRequest(
              filter = Some(
                TransactionFilter(filtersByParty(false))
              ),
              activeAtOffset =
                aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.end(),
            )
          )

          val activeContractsResponse =
            makeJsonApiV2Request(
              "/v2/state/active-contracts",
              getActiveContractsPayload,
              io.circe.Decoder.decodeSeq(JsStateServiceCodecs.jsGetActiveContractsResponseRW),
            )

          activeContractsResponse should have size 4 // 2 amulets, 2 sample holdings

          val getUpdatesPayload = JsUpdateServiceCodecs.getUpdatesRequest(
            GetUpdatesRequest(
              updateFormat = Some(
                UpdateFormat(includeTransactions =
                  Some(
                    TransactionFormat(
                      transactionShape = TRANSACTION_SHAPE_LEDGER_EFFECTS,
                      eventFormat = Some(EventFormat(filtersByParty(true))),
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

          (activeContractsResponse, getUpdatesResponse)
        },
      )

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
        alice.toProtoPrimitive -> "party::normalized",
        dsoParty.toProtoPrimitive -> "dso::normalized",
        aliceValidatorBackend.getValidatorPartyId().toProtoPrimitive -> "validator::normalized",
      )
      val dateFields =
        Seq(
          "expiresAt",
          "lastRenewedAt",
          "validFrom",
          "requestedAt",
          "executeBefore",
          "opensAt",
          "targetClosesAt",
        )
      def replaceStringsInJson(viewValue: Json) = {
        val current = viewValue.spaces2SortKeys
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
              sv1ScanBackend.getAmuletRules().contractId.contractId,
              replaceContractIdWithStableString(
                sv1ScanBackend.getAmuletRules().contractId.contractId
              ).toString,
            )

        val stableDates = dateFields.foldLeft(stableContractIdsAndParties) {
          case (acc, fieldToReplace) =>
            acc.replaceAll(
              s"\"${fieldToReplace}\" : \"(.*)\"",
              s"\"${fieldToReplace}\" : \"2025-06-18T00:00:00.000000Z\"",
            )
        }

        io.circe.parser
          .parse(stableDates)
          .valueOrFail("expected valid json after normalization")
      }

      val normalizedHoldings = activeContractsResponse.map(entry =>
        entry.copy(contractEntry = entry.contractEntry match {
          case active: JsContractEntry.JsActiveContract =>
            active.copy(
              createdEvent = active.createdEvent.copy(
                contractId =
                  replaceContractIdWithStableString(active.createdEvent.contractId).toString,
                createdAt = protobuf.timestamp.Timestamp.of(0, 0),
                createdEventBlob = ByteString.empty(),
                interfaceViews = active.createdEvent.interfaceViews.map(iv =>
                  iv.copy(viewValue =
                    Some(
                      replaceStringsInJson(
                        iv.viewValue.valueOrFail("Expected view value to be available.")
                      )
                    )
                  )
                ),
                nodeId = 1,
                offset = 1,
                signatories = active.createdEvent.signatories.map(expectedParties),
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

      replaceOrFail(
        "token-standard/cli/__tests__/mocks/data/holdings.json",
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
              effectiveAt = protobuf.timestamp.Timestamp.of(idx.toLong, 0),
              offset = idx.toLong,
              synchronizerId = "sync::normalized",
              traceContext = None,
              recordTime = protobuf.timestamp.Timestamp.of(idx.toLong, 0),
              events = transaction.value.events.zipWithIndex.map {
                case (created: JsEvent.CreatedEvent, eventIdx) =>
                  created.copy(
                    offset = idx.toLong,
                    contractId = replaceContractIdWithStableString(created.contractId).toString,
                    createArgument = created.createArgument.map(replaceStringsInJson),
                    createdEventBlob = ByteString.empty(),
                    interfaceViews = created.interfaceViews.map(view =>
                      view.copy(viewValue =
                        Some(
                          replaceStringsInJson(
                            view.viewValue.valueOrFail("Expected view value to be available.")
                          )
                        )
                      )
                    ),
                    witnessParties = created.witnessParties.map(expectedParties),
                    signatories = created.signatories.map(expectedParties),
                    observers = created.observers.map(expectedParties),
                    createdAt = protobuf.timestamp.Timestamp.of(idx.toLong, eventIdx),
                  )
                case (archived: JsEvent.ArchivedEvent, _) =>
                  archived.copy(
                    offset = idx.toLong,
                    contractId = replaceContractIdWithStableString(archived.contractId).toString,
                    witnessParties = archived.witnessParties.map(expectedParties),
                  )
                case (exercised: JsEvent.ExercisedEvent, _) =>
                  exercised.copy(
                    offset = idx.toLong,
                    contractId = replaceContractIdWithStableString(exercised.contractId).toString,
                    choiceArgument = replaceStringsInJson(exercised.choiceArgument),
                    actingParties = exercised.actingParties.map(expectedParties),
                    witnessParties = exercised.witnessParties.map(expectedParties),
                    exerciseResult = replaceStringsInJson(exercised.exerciseResult),
                  )
              },
            )
          )
        }

      replaceOrFail(
        "token-standard/cli/__tests__/mocks/data/txs.json",
        normalizedUpdates.map(JsGetUpdatesResponse(_)),
        io.circe.Encoder.encodeSeq(JsUpdateServiceCodecs.jsGetUpdatesResponse),
      )
    }

  }

  private val jsonApiPort = 16201
  private def makeJsonApiV2Request[R](subPath: String, payload: Json, decode: Decoder[R])(implicit
      env: SpliceTestConsoleEnvironment
  ): R = {
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
    io.circe.parser
      .parse(rawResponse)
      .valueOrFail("failed to parse json")
      .as[R](decode)
      .valueOrFail(s"Failed to decode $rawResponse")
  }

}
