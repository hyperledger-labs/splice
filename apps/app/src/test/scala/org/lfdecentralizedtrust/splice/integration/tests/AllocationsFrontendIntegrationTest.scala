package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
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
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class AllocationsFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TokenStandardTest {

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
        textField("create-allocation-transfer-leg-id-0").underlying
          .sendKeys(wantedAllocation.transferLegId)
        textField("create-allocation-settlement-ref-id").underlying
          .sendKeys(wantedAllocation.settlement.settlementRef.id)
        eventuallyClickOn(id("create-allocation-transfer-leg-sender-0"))
        setAnsField(
          textField("create-allocation-transfer-leg-sender-0"),
          sender.toProtoPrimitive,
          sender.toProtoPrimitive,
        )
        eventuallyClickOn(id("create-allocation-transfer-leg-receiver-0"))
        setAnsField(
          textField("create-allocation-transfer-leg-receiver-0"),
          receiver.toProtoPrimitive,
          receiver.toProtoPrimitive,
        )
        eventuallyClickOn(id("create-allocation-settlement-executor-0"))
        setAnsField(
          textField("create-allocation-settlement-executor-0"),
          validatorPartyId.toProtoPrimitive,
          validatorPartyId.toProtoPrimitive,
        )
        eventuallyClickOn(id("create-allocation-0-amulet-amount"))
        numberField("create-allocation-0-amulet-amount").value = ""
        numberField("create-allocation-0-amulet-amount").underlying.sendKeys(
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
        textField("create-allocation-settlement-settle-at").underlying
          .sendKeys(
            allocationTimestampFormat.format(
              wantedAllocation.settlement.settleBefore.atOffset(ZoneOffset.UTC)
            )
          )

        eventuallyClickOn(id("create-allocation-submit-button"))
      },
    )(
      "the allocation is created",
      _ => {
        val allocation = findAll(className("allocation")).toSeq.loneElement

        checkSettlementInfo(
          allocation,
          wantedAllocation.settlement.settlementRef.id,
          wantedAllocation.settlement.settlementRef.cid.map(_.contractId).toScala,
          Seq(wantedAllocation.settlement.executor),
        )

        checkTransferLegs(
          allocation,
          Map(
            wantedAllocation.transferLegId -> wantedAllocation.transferLeg
          ),
        )
      },
    )
  }

  "A wallet UI" should {

    "see, accept and withdraw allocation requests" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceTransferAmount = BigDecimal(5)

      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
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

        val allocationRequestElement = clue("check that the allocation request is shown") {
          eventually() {
            val allocationRequest = findAll(className("allocation-request")).toSeq.loneElement

            checkSettlementInfo(
              allocationRequest,
              "OTCTradeProposal", // hardcoded in daml
              Some(otcTrade.trade.data.tradeCid.contractId),
              Seq(venueParty.toProtoPrimitive),
            )

            checkTransferLegs(allocationRequest, otcTrade.trade.data.transferLegs.asScala.toMap)

            allocationRequest
          }
        }

        clue("sanity check: alice has no allocations yet") {
          aliceWalletClient
            .listAmuletAllocations() shouldBe empty withClue "alice AmuletAllocations"
        }

        val (_, allocationElement) = actAndCheck(
          "click on accepting the allocation request", {
            val (aliceTransferLegId, _) =
              otcTrade.aliceRequest.transferLegs.asScala
                .find(_._2.sender == aliceParty.toProtoPrimitive)
                .valueOrFail("Couldn't find alice's transfer leg")
            eventuallyClickOn(
              id(s"transfer-leg-${otcTrade.trade.id.contractId}-$aliceTransferLegId-accept")
            )
          },
        )(
          "the allocation is shown",
          { _ =>
            val allocation = findAll(className("allocation")).toSeq.loneElement

            checkSettlementInfo(
              allocation,
              "OTCTradeProposal", // hardcoded in daml
              Some(otcTrade.trade.data.tradeCid.contractId),
              Seq(venueParty.toProtoPrimitive),
            )

            checkTransferLegs(allocation, otcTrade.trade.data.transferLegs.asScala.toMap)

            allocation
          },
        )

        actAndCheck(
          "click on withdrawing the allocation", {
            click on allocationElement
              .findChildElement(className("allocation-withdraw"))
              .valueOrFail("Could not find withdraw button for allocation")
          },
        )(
          "the allocation is not shown anymore",
          _ => {
            findAll(className("allocation")).toSeq shouldBe empty withClue "Allocation Cards"
          },
        )

        actAndCheck(
          "click on rejecting the allocation request", {
            click on allocationRequestElement
              .findChildElement(className("allocation-request-reject"))
              .valueOrFail("Could not find reject button for allocation request")
          },
        )(
          "the allocation request is not shown anymore",
          _ => {
            findAll(
              className("allocation-request")
            ).toSeq shouldBe empty withClue "Allocation Request Cards"
          },
        )
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
        eventuallyClickOn(id("navlink-allocations"))
      },
    )(
      "allocations page is shown",
      _ => {
        currentUrl should endWith("/allocations")
      },
    )
  }

  private def checkSettlementInfo(
      parent: Element,
      id: String,
      cid: Option[String],
      executors: Seq[String],
  ) = {
    seleniumText(
      parent.childElement(className("settlement-id"))
    ) should be(
      s"SettlementRef id: $id"
    )
    cid.foreach(cid =>
      seleniumText(
        parent.childElement(className("settlement-cid"))
      ) should be(s"SettlementRef cid: $cid")
    )
    val executorElements = parent.findAllChildElements(className("settlement-executor")).toSeq
    executorElements.map(seleniumText).zip(executors).foreach { case (actual, expected) =>
      actual should matchText(expected)
    }
  }

  private def checkTransferLegs(
      parent: Element,
      transferLegs: Map[String, TransferLeg],
  ) = {
    val rows =
      parent.findAllChildElements(className("allocation-row")).toSeq
    rows.zip(transferLegs.toSeq.sortBy(_._1)).foreach { case (row, (legId, transferLeg)) =>
      seleniumText(
        row.childElement(className("allocation-legid"))
      ) should matchText(legId)
      seleniumText(
        row.childElement(className("allocation-amount-instrument"))
      ) should matchText(
        s"${transferLeg.amount.intValue()} ${transferLeg.instrumentId.id}"
      )
      seleniumText(
        row.childElement(className("allocation-sender"))
      ) should matchText(transferLeg.sender)
      seleniumText(
        row.childElement(className("allocation-receiver"))
      ) should matchText(transferLeg.receiver)
    }
  }
}
