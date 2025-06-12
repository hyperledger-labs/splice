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

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.Optional

class AllocationsFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  private val amuletPrice = 2
  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.toDouble)
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)

  private def createAllocation(sender: PartyId)(implicit
      ev: SpliceTestConsoleEnvironment,
      webDriver: WebDriverType,
  ) = {
    val validatorPartyId = aliceValidatorBackend.getValidatorPartyId()
    val receiver = validatorPartyId
    val settleAndAllocateBefore = LocalDateTime
      .now()
      .plusSeconds(3600)
      .truncatedTo(ChronoUnit.MINUTES)
      .toInstant(ZoneOffset.UTC)
    def wantedAllocation(requestedAt: Instant) = new AllocationSpecification(
      new SettlementInfo(
        validatorPartyId.toProtoPrimitive,
        new SettlementReference("some_reference", Optional.empty),
        requestedAt,
        settleAndAllocateBefore,
        settleAndAllocateBefore,
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
    // use the data here to create, but ignore the requestedAt field
    val create = wantedAllocation(Instant.now())

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

    actAndCheck(
      "create allocation", {
        textField("create-allocation-transfer-leg-id").underlying
          .sendKeys(create.transferLegId)
        textField("create-allocation-settlement-ref-id").underlying
          .sendKeys(create.settlement.settlementRef.id)
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
        click on "amulet-amount"
        numberField("amulet-amount").value = ""
        numberField("amulet-amount").underlying.sendKeys(
          create.transferLeg.amount.toString
        )

        setDateTime(
          "alice",
          "create-allocation-settlement-settle-before",
          create.settlement.settleBefore,
        )
        setDateTime(
          "alice",
          "create-allocation-settlement-allocate-before",
          create.settlement.allocateBefore,
        )

        setMeta(create.settlement.meta, "settlement")
        setMeta(create.transferLeg.meta, "transfer-leg")

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

        specification should be(wantedAllocation(specification.settlement.requestedAt))
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

    "create a token standard allocation" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(1000)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        createAllocation(aliceUserParty)
      }
    }

  }
}
