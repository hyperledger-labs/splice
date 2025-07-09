package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.event.CreatedEvent.toJavaProto
import com.daml.ledger.javaapi.data.CreatedEvent
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation.AmuletAllocation
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1.{
  AllocationSpecification,
  SettlementInfo,
  TransferLeg,
  Reference as SettlementReference,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1.InstrumentId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.Metadata
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  FrontendLoginUtil,
  SpliceUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDateTime, ZoneOffset}
import java.util.Optional
import scala.util.Random

class AllocationsFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TokenStandardTest {

  private val tokenStandardTestDarPath =
    "token-standard/splice-token-standard-test/.daml/dist/splice-token-standard-test-current.dar"

  private val amuletPrice = 2
  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.toDouble)
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
          splitwellValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardTestDarPath)
        }
      })

  private def createAllocation(sender: PartyId)(implicit
      ev: SpliceTestConsoleEnvironment,
      webDriver: WebDriverType,
  ) = {
    val validatorPartyId = aliceValidatorBackend.getValidatorPartyId()
    val receiver = validatorPartyId
    val now = LocalDateTime
      .now()
      .truncatedTo(ChronoUnit.MICROS)
      .toInstant(ZoneOffset.UTC)
    val requestedAt = now.minusSeconds(1800)
    val allocateBefore = now.plusSeconds(3600)
    val settleBefore = now.plusSeconds(3600 * 2)

    val wantedAllocation = new AllocationSpecification(
      new SettlementInfo(
        validatorPartyId.toProtoPrimitive,
        new SettlementReference("some_reference", Optional.empty),
        requestedAt,
        allocateBefore,
        settleBefore,
        new Metadata(java.util.Map.of("k1", "v1", "k2", "v2")),
      ),
      "some_transfer_leg_id",
      new TransferLeg(
        sender.toProtoPrimitive,
        validatorPartyId.toProtoPrimitive,
        BigDecimal(12).bigDecimal.setScale(10),
        new InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        new Metadata(java.util.Map.of("k3", "v3")),
      ),
    )

    browseToAllocationsPage()

    actAndCheck(
      "create allocation", {
        textField("create-allocation-transfer-leg-id").underlying
          .sendKeys(wantedAllocation.transferLegId)
        textField("create-allocation-settlement-ref-id").underlying
          .sendKeys(wantedAllocation.settlement.settlementRef.id)
        click on "create-allocation-transfer-leg-receiver"
        setAnsField(
          textField("create-allocation-transfer-leg-receiver"),
          receiver.toProtoPrimitive,
          receiver.toProtoPrimitive,
        )
        click on "create-allocation-settlement-executor"
        setAnsField(
          textField("create-allocation-settlement-executor"),
          validatorPartyId.toProtoPrimitive,
          validatorPartyId.toProtoPrimitive,
        )
        click on "create-allocation-amulet-amount"
        numberField("create-allocation-amulet-amount").value = ""
        numberField("create-allocation-amulet-amount").underlying.sendKeys(
          wantedAllocation.transferLeg.amount.toString
        )

        val allocationTimestampFormat =
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        textField("create-allocation-settlement-requested-at").underlying
          .sendKeys(
            allocationTimestampFormat.format(
              wantedAllocation.settlement.requestedAt.atOffset(ZoneOffset.UTC)
            )
          )
        textField("create-allocation-settlement-settle-before").underlying
          .sendKeys(
            allocationTimestampFormat.format(
              wantedAllocation.settlement.settleBefore.atOffset(ZoneOffset.UTC)
            )
          )
        textField("create-allocation-settlement-allocate-before").underlying
          .sendKeys(
            allocationTimestampFormat.format(
              wantedAllocation.settlement.allocateBefore.atOffset(ZoneOffset.UTC)
            )
          )

        setMeta(wantedAllocation.settlement.meta, "settlement")
        setMeta(wantedAllocation.transferLeg.meta, "transfer-leg")

        click on "create-allocation-submit-button"
      },
    )(
      "the allocation is created",
      _ => {
        // TODO (#1106): check in the FE as opposed to checking the ledger
        val allocation =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
            .of_party(
              party = sender,
              filterTemplates = Seq(AmuletAllocation.TEMPLATE_ID).map(TemplateId.fromJavaIdentifier),
            )
            .loneElement

        val specification = Contract
          .fromCreatedEvent(AmuletAllocation.COMPANION)(
            CreatedEvent.fromProto(toJavaProto(allocation.event))
          )
          .getOrElse(fail(s"Failed to parse allocation contract: $allocation"))
          .payload
          .allocation

        specification should be(wantedAllocation)
      },
    )
  }

  private def setMeta(meta: Metadata, idPrefix: String)(implicit webDriver: WebDriverType) = {
    import scala.jdk.CollectionConverters.*

    meta.values.asScala.zipWithIndex.foreach { case ((key, value), index) =>
      click on s"$idPrefix-add-meta"
      textField(s"$idPrefix-meta-key-$index").underlying.sendKeys(key)
      textField(s"$idPrefix-meta-value-$index").underlying.sendKeys(value)
    }
  }

  "A wallet UI" should {

    "see allocation requests" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceTransferAmount = BigDecimal(5)

      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobAnsName = perTestCaseName("bob")
      val bobAnsDisplay = expectedAns(bobParty, bobAnsName)
      createAnsEntry(bobAnsExternalClient, bobAnsName, bobWalletClient)
      val bobTransferAmount = BigDecimal(6)

      val venuePartyHint = s"venue-party-${Random.nextInt()}"
      val venueParty = splitwellValidatorBackend.onboardUser(
        splitwellWalletClient.config.ledgerApiUser,
        Some(
          PartyId.tryFromProtoPrimitive(
            s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
          )
        ),
      )

      aliceWalletClient.tap(1000)
      bobWalletClient.tap(1000)

      val otcTrade = createAllocationRequestViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        browseToAllocationsPage()

        clue("check that the allocation request is shown") {
          eventually() {
            val allocationRequest = findAll(className("allocation-request")).toSeq.loneElement

            seleniumText(
              allocationRequest.childElement(className("allocation-request-id"))
            ) should be(
              // first part is hardcoded in daml
              s"OTCTradeProposal - ${otcTrade.trade.data.tradeCid.contractId}"
            )
            allocationRequest
              .childElement(className("allocation-request-amount-to"))
              .text should be(
              "5 AMT to"
            )
            seleniumText(
              allocationRequest.childElement(className("allocation-receiver"))
            ) should matchText(bobAnsDisplay)
            seleniumText(
              allocationRequest.childElement(className("allocation-executor"))
            ) should matchText(venueParty.toProtoPrimitive)
          }
        }
      }
    }

    "create a token standard allocation manually" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(1000)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        createAllocation(aliceUserParty)
      }
    }

  }

  private def browseToAllocationsPage()(implicit driver: WebDriverType) = {
    actAndCheck(
      "go to allocations page", {
        click on "navlink-allocations"
      },
    )(
      "allocations page is shown",
      _ => {
        currentUrl should endWith("/allocations")
      },
    )
  }
}
