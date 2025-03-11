package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.commands.Command.Command.Create
import com.daml.ledger.api.v2.commands.{Command, CreateCommand}
import com.daml.ledger.api.v2.state_service.GetActiveContractsRequest
import com.daml.ledger.api.v2.transaction_filter.CumulativeFilter.IdentifierFilter
import com.daml.ledger.api.v2.transaction_filter.{
  CumulativeFilter,
  Filters,
  InterfaceFilter,
  TransactionFilter,
}
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.http.json.v2.{
  JsContractEntry,
  JsGetActiveContractsResponse,
  JsStateServiceCodecs,
}
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.google.protobuf
import com.google.protobuf.ByteString
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.Post
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.test.dummyholding.DummyHolding
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{IntegrationTest}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

class TokenStandardCliTestDataIntegrationTest
    extends IntegrationTest
    with HasActorSystem
    with HasExecutionContext {

  private val sampleHoldingDarPath = Paths
    .get(
      "token-standard/examples/splice-token-test-dummy-holding/.daml/dist/splice-api-token-sample-holding-current.dar"
    )
    .toAbsolutePath
    .toString

  private val isCI = sys.env.contains("CI")

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
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

  "Token Standard CLI" should {

    "have up-to-date test data" in { implicit env =>
      val party = aliceValidatorBackend.getValidatorPartyId()

      val (_, decodedResponse) = actAndCheck(
        "Create some holdings", {
          // Amulet holdings
          aliceValidatorWalletClient.tap(BigDecimal("10.0"))
          aliceValidatorWalletClient.tap(BigDecimal("20.0"))

          // SampleHolding holdings
          Seq("30.0", "40.0").foreach { amount =>
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.commands
              .submit(
                actAs = Seq(party),
                readAs = Seq(party),
                optTimeout = None,
                commands = Seq(
                  Command(
                    Create(
                      CreateCommand.fromJavaProto(
                        new DummyHolding(
                          party.toProtoPrimitive,
                          party.toProtoPrimitive,
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
        "holdings are returned by interface filter and can be added to the file",
        _ => {
          val getActiveContractsPayload = JsStateServiceCodecs.getActiveContractsRequestRW(
            GetActiveContractsRequest(
              filter = Some(
                TransactionFilter(
                  Map(
                    party.toProtoPrimitive -> Filters(
                      Seq(
                        CumulativeFilter(
                          IdentifierFilter.InterfaceFilter(
                            InterfaceFilter(
                              Some(
                                TemplateId
                                  .fromJavaIdentifier(holdingv1.Holding.TEMPLATE_ID)
                                  .toIdentifier
                              ),
                              includeCreatedEventBlob = true,
                              includeInterfaceView = true,
                            )
                          )
                        )
                      )
                    )
                  )
                )
              ),
              activeAtOffset =
                aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.end(),
              verbose = true,
            )
          )

          val response = Http()
            .singleRequest(
              Post(
                "http://localhost:6201/v2/state/active-contracts",
                getActiveContractsPayload.noSpaces,
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
          val raw = Unmarshal(response).to[String].futureValue
          val decodedResponse = io.circe.parser
            .parse(raw)
            .valueOrFail("failed to parse json")
            .as[Seq[JsGetActiveContractsResponse]](
              io.circe.Decoder.decodeSeq(JsStateServiceCodecs.jsGetActiveContractsResponseRW)
            )
            .valueOrFail(s"failed to decode $raw")

          decodedResponse should have size (4)

          decodedResponse
        },
      )

      val expectedParties = Map(
        party.toProtoPrimitive -> "party::normalized",
        dsoParty.toProtoPrimitive -> "dso::normalized",
      )
      val normalized = decodedResponse.map(entry =>
        entry.copy(contractEntry = entry.contractEntry match {
          case active: JsContractEntry.JsActiveContract =>
            active.copy(
              createdEvent = active.createdEvent.copy(
                contractId = "contractid",
                createdAt = protobuf.timestamp.Timestamp.of(0, 0),
                createdEventBlob = ByteString.empty(),
                interfaceViews = active.createdEvent.interfaceViews.map(iv =>
                  iv.copy(viewValue = {
                    val current =
                      iv.viewValue
                        .valueOrFail("Expected view value to be available.")
                        .spaces2SortKeys
                    val normalized = expectedParties.foldLeft(current) {
                      case (acc, (party, normalized)) =>
                        acc.replace(party, normalized)
                    }
                    Some(
                      io.circe.parser
                        .parse(normalized)
                        .valueOrFail("expected valid json after normalization")
                    )
                  })
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
              s"Should be active contract but it's not? $decodedResponse"
            )
        })
      )
      val prettyNormalized =
        io.circe.Encoder
          .encodeSeq(JsStateServiceCodecs.jsGetActiveContractsResponseRW)(
            normalized
          )
          .spaces2SortKeys

      val targetPath = Paths.get("token-standard/cli/__tests__/mocks/data/holdings.json")
      if (isCI) {
        val actual = Files.readString(targetPath)

        actual should be(prettyNormalized)
      } else {
        Files.writeString(targetPath, prettyNormalized)
      }
    }

  }

}
