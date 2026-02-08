package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.console.ScanAppBackendReference
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpDef
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpiredDevelopmentFundCouponTrigger,
}
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger

import java.time.Duration

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_21
class DevelopmentFundCouponIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil
    with WalletTestUtil {

  private val unclaimedDevelopmentFundCouponsThreshold = 3

  // TODO(#3549): scan_txlog does not handle development fund coupons correctly, so this is currently disabled.
  //  We should decide whether we want to fix scan_txlog for DevelopmentFundCoupons, or narrow its scope enough that we won't care
  //  (i.e. don't try to be complete, don't use it to assert on scan's aggregates, etc)
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .addConfigTransform((_, config) => {
        val aliceParticipant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("alice_validator_user")
        val alicePartyHint =
          config.validatorApps(InstanceName.tryCreate("aliceValidator")).validatorPartyHint.value
        val alicePartyId = PartyId
          .tryFromProtoPrimitive(
            s"$alicePartyHint::${aliceParticipant.split("::").last}"
          )
        ConfigTransforms.withDevelopmentFundManager(alicePartyId)(config)
      })
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            unclaimedDevelopmentFundCouponsThreshold = unclaimedDevelopmentFundCouponsThreshold
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.withDevelopmentFundPercentage(0.05)(config)
      )

  "Issuing and merging unclaimed development fund coupons" in { implicit env =>
    val (_, couponAmount) = actAndCheck(
      "Advance 5 rounds", {
        Range(0, 5).foreach(_ => advanceRoundsByOneTickViaAutomation())
      },
    )(
      "5 UnclaimedDevelopmentFundCoupons are created, and the trigger does not merge the coupons, " +
        "as it only acts when the number of coupons is ≥ 2 × threshold",
      _ => {
        val coupons = sv1ScanBackend.listUnclaimedDevelopmentFundCoupons()
        coupons should have size 5
        aliceValidatorBackend.scanProxy.listUnclaimedDevelopmentFundCoupons() shouldBe coupons
        BigDecimal(coupons.head.contract.payload.amount)
      },
    )

    actAndCheck(
      "Advance one round to create one more UnclaimedDevelopmentFundCoupon, reaching 2 × threshold coupons",
      advanceRoundsByOneTickViaAutomation(),
    )(
      "The MergeUnclaimedDevelopmentFundCouponsTrigger is triggered and merges the smallest three coupons (threshold), " +
        "while keeping the remaining coupons unchanged.",
      _ => {
        assertUnclaimedDevelopmentCouponAmounts(
          Seq(
            couponAmount,
            couponAmount,
            couponAmount,
            couponAmount * 3,
          )
        )
      },
    )

    actAndCheck(
      "Advance two rounds to create two more UnclaimedDevelopmentFundCoupon, " +
        "reaching 2 × threshold coupons and triggering a second merge",
      Range(0, 2).foreach(_ => advanceRoundsByOneTickViaAutomation()),
    )(
      "The MergeUnclaimedDevelopmentFundCouponsTrigger merges the `threshold` smallest coupons",
      _ => {
        assertUnclaimedDevelopmentCouponAmounts(
          Seq(
            couponAmount,
            couponAmount,
            couponAmount * 3,
            couponAmount * 3,
          )
        )
      },
    )
  }

  "Managing development fund coupons" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val unclaimedDevelopmentFundCouponsToMint = Seq(10.0, 10.0, 30.0, 30.0).map(BigDecimal(_))
    val unclaimedDevelopmentFundCouponTotal = unclaimedDevelopmentFundCouponsToMint.sum
    val aliceValidatorParty = onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val fundManger = aliceValidatorParty
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val beneficiary = bobParty
    val developmentFundCouponAmount = BigDecimal(40.0)
    val expiresAt = CantonTimestamp.now().plus(Duration.ofDays(1))
    val reason = "Bob has contributed to the Daml repo"

    clue("Mint some unclaimed development fund coupons") {
      unclaimedDevelopmentFundCouponsToMint.foreach { amount =>
        createUnclaimedDevelopmentFundCoupon(
          sv1ValidatorBackend.participantClientWithAdminToken,
          sv1UserId,
          amount,
        )
      }
      eventually()(
        sv1ScanBackend
          .listUnclaimedDevelopmentFundCoupons()
          .map(coupon => BigDecimal(coupon.contract.payload.amount))
          .sum shouldBe unclaimedDevelopmentFundCouponTotal
      )
    }

    // Allocation
    /////////////

    clue("An invalid fund manager cannot allocate a development fund coupon") {
      assertThrowsAndLogsCommandFailures(
        bobWalletClient.allocateDevelopmentFundCoupon(
          beneficiary,
          developmentFundCouponAmount,
          expiresAt,
          reason,
        ),
        logEntry => {
          logEntry.errorMessage should (include("400 Bad Request") and include(
            "Invalid fund manager"
          ))
          logEntry.errorMessage should not include "DAML_FAILURE"
        },
      )
    }

    clue(
      "A development fund coupon cannot be allocated when the total of unclaimed development " +
        "coupon is insufficient"
    ) {
      assertThrowsAndLogsCommandFailures(
        aliceValidatorWalletClient.allocateDevelopmentFundCoupon(
          beneficiary,
          unclaimedDevelopmentFundCouponsToMint.sum + BigDecimal(1.0),
          expiresAt,
          reason,
        ),
        logEntry => {
          logEntry.errorMessage should (include("400 Bad Request") and include(
            "The total amount of unclaimed development coupons is insufficient to cover the amount requested"
          ))
          logEntry.errorMessage should not include "DAML_FAILURE"
        },
      )
    }

    val bobUserName = bobWalletClient.config.ledgerApiUser
    val bobMergeAmuletsTrigger =
      bobValidatorBackend
        .userWalletAutomation(bobUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    setTriggersWithin(
      triggersToPauseAtStart = Seq(bobMergeAmuletsTrigger)
    ) {
      val (_, developmentFundCouponCid) = actAndCheck(
        "As the fund manager, Alice allocates one development fund coupon", {
          aliceValidatorWalletClient.allocateDevelopmentFundCoupon(
            beneficiary,
            developmentFundCouponAmount,
            expiresAt,
            reason,
          )
        },
      )(
        "One development fund coupon is allocated and the total of unclaimed development fund coupons has decreased",
        _ => {
          // Beneficiary can list their coupons
          val activeDevelopmentFundCouponContracts =
            bobWalletClient.listActiveDevelopmentFundCoupons()
          activeDevelopmentFundCouponContracts.length shouldBe 1

          // Fund manager can list their coupons
          aliceValidatorWalletClient
            .listActiveDevelopmentFundCoupons()
            .length shouldBe 1

          // Verify the coupon
          val developmentFundCouponContract = activeDevelopmentFundCouponContracts.head
          val developmentFundCoupon = developmentFundCouponContract.payload
          BigDecimal(developmentFundCoupon.amount) shouldBe developmentFundCouponAmount
          developmentFundCoupon.beneficiary shouldBe beneficiary.toProtoPrimitive
          developmentFundCoupon.fundManager shouldBe fundManger.toProtoPrimitive

          // Verify that the total of unclaimed development fund coupons has decreased
          // Note: HttpWalletHandler selects the largest unclaimed development fund coupons for the allocation,
          // as they are the most stable.
          assertUnclaimedDevelopmentCouponAmounts(Seq(10.0, 10.0, 20.0))

          // Fund manager can list their coupons
          aliceValidatorWalletClient
            .listActiveDevelopmentFundCoupons()
            .length shouldBe 1

          developmentFundCouponContract.contractId
        },
      )

      // Withdrawal
      //////////////

      val withdrawalReason = "Bob's PR in the Daml repo broke CI"
      clue("The coupon's beneficiary cannot withdraw a development fund coupon") {
        assertThrowsAndLogsCommandFailures(
          bobWalletClient
            .withdrawDevelopmentFundCoupon(developmentFundCouponCid, withdrawalReason),
          logEntry => {
            logEntry.errorMessage should (include("400 Bad Request") and include(
              "Invalid controller"
            ))
            logEntry.errorMessage should not include "DAML_AUTHORIZATION_ERROR"
          },
        )
      }

      actAndCheck(
        "As the fund manager, Alice withdraws a development fund coupon", {
          aliceValidatorWalletClient
            .withdrawDevelopmentFundCoupon(developmentFundCouponCid, withdrawalReason)
        },
      )(
        "The coupon is withdrawn, a new unclaimed development fund coupon is created and " +
          "the archived coupon is listed in listDevelopmentFundCouponHistory",
        _ => {
          // Verify that the coupon is archived
          aliceValidatorWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
          // Verify that a new unclaimed development fund coupon was created
          assertUnclaimedDevelopmentCouponAmounts(
            Seq(10.0, 10.0, 20.0, developmentFundCouponAmount.toDouble)
          )

          // Verify that the withdrawn coupon is listed in listDevelopmentFundCouponHistory as withdrawn
          val expectedStatus = httpDef.ArchivedDevelopmentFundCouponStatus(
            httpDef.ArchivedDevelopmentFundCouponWithdrawnStatus(
              status = httpDef.ArchivedDevelopmentFundCouponWithdrawnStatus.Status.Withdrawn,
              reason = withdrawalReason,
            )
          )
          // As the fund manager, Alice can view the withdrawn coupon
          aliceValidatorWalletClient
            .listDevelopmentFundCouponHistory(None, 5)
            .developmentFundCouponHistory
            .map(_.status) shouldBe Seq(expectedStatus)
          // As the beneficiary, Bob can view the withdrawn coupon
          bobWalletClient
            .listDevelopmentFundCouponHistory(None, 5)
            .developmentFundCouponHistory
            .map(_.status) shouldBe Seq(expectedStatus)
        },
      )
    }
  }

  "Claiming development fund coupons" in { implicit env =>
    onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val beneficiary = bobParty
    val initialUnclaimedDevelopmentFundCouponAmount = 1000
    val developmentFundCouponAmount = 40.0
    val expiresAt = CantonTimestamp.now().plus(Duration.ofDays(1))
    val reason = "Bob has contributed to the Daml repo"

    val bobUserName = bobWalletClient.config.ledgerApiUser
    val bobMergeAmuletsTrigger =
      bobValidatorBackend
        .userWalletAutomation(bobUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    clue("Mint one unclaimed development fund coupon") {
      createUnclaimedDevelopmentFundCoupon(
        sv1ValidatorBackend.participantClientWithAdminToken,
        sv1UserId,
        initialUnclaimedDevelopmentFundCouponAmount,
      )
    }

    val (_, unclaimedDevelopmentFundCouponTotalAfterAllocation) = setTriggersWithin(
      triggersToPauseAtStart = Seq(bobMergeAmuletsTrigger)
    ) {
      actAndCheck(
        "Allocate one development fund coupon", {
          aliceValidatorWalletClient.allocateDevelopmentFundCoupon(
            beneficiary,
            developmentFundCouponAmount,
            expiresAt,
            reason,
          )
        },
      )(
        "A coupon is created and the total unclaimed development fund coupon amount decreases",
        _ => {
          aliceValidatorWalletClient
            .listActiveDevelopmentFundCoupons()
            .map(_.payload.expiresAt) should not be empty
          val expectedUnclaimedDevelopmentFundCouponTotal =
            initialUnclaimedDevelopmentFundCouponAmount - developmentFundCouponAmount
          getUnclaimedDevelopmentFundCouponTotal(
            sv1ScanBackend
          ) shouldBe expectedUnclaimedDevelopmentFundCouponTotal

          expectedUnclaimedDevelopmentFundCouponTotal
        },
      )
    }

    clue("Coupon is collected by the collect rewards automation") {
      eventually() {
        aliceValidatorWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
        checkWallet(
          bobParty,
          bobWalletClient,
          Seq(exactly(34)), // 40 - fees
        )
      }
    }

    clue(
      "The total unclaimed development fund coupon amount stays the same"
    ) {
      eventually() {
        getUnclaimedDevelopmentFundCouponTotal(
          sv1ScanBackend
        ) shouldBe unclaimedDevelopmentFundCouponTotalAfterAllocation
      }
    }

    clue("Collected coupon is listed in listDevelopmentFundCouponHistory as claimed") {
      val expectedStatus = httpDef.ArchivedDevelopmentFundCouponStatus(
        httpDef.ArchivedDevelopmentFundCouponClaimedStatus(
          status = httpDef.ArchivedDevelopmentFundCouponClaimedStatus.Status.Claimed
        )
      )
      // As the fund manager, Alice can view the claimed coupon
      aliceValidatorWalletClient
        .listDevelopmentFundCouponHistory(None, 10)
        .developmentFundCouponHistory
        .map(_.status) shouldBe Seq(expectedStatus)
      // As the beneficiary, Bob can view the claimed coupon
      bobWalletClient
        .listDevelopmentFundCouponHistory(None, 10)
        .developmentFundCouponHistory
        .map(_.status) shouldBe Seq(expectedStatus)
    }
  }

  "Expiring a development fund coupon" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val beneficiary = bobParty
    val initialUnclaimedDevelopmentFundCouponAmount = 1000.0
    val developmentFundCouponAmount = 40.0
    val expiresAt = CantonTimestamp.now().plus(Duration.ofSeconds(5))
    val reason = "Bob has contributed to the Daml repo"

    val bobUserName = bobWalletClient.config.ledgerApiUser
    val bobMergeAmuletsTrigger =
      bobValidatorBackend
        .userWalletAutomation(bobUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    val expiredDevelopmentFundCouponTriggers =
      activeSvs.map(
        _.dsoDelegateBasedAutomation.trigger[ExpiredDevelopmentFundCouponTrigger]
      )

    clue("Mint one unclaimed development fund coupon") {
      createUnclaimedDevelopmentFundCoupon(
        sv1ValidatorBackend.participantClientWithAdminToken,
        sv1UserId,
        initialUnclaimedDevelopmentFundCouponAmount,
      )
    }

    setTriggersWithin(
      triggersToPauseAtStart = Seq(bobMergeAmuletsTrigger)
    ) {
      val (_, unclaimedDevelopmentFundCouponTotalAfterExpiration) = setTriggersWithin(
        triggersToPauseAtStart = expiredDevelopmentFundCouponTriggers
      ) {
        actAndCheck(
          "Allocate one development fund coupon", {
            aliceValidatorWalletClient.allocateDevelopmentFundCoupon(
              beneficiary,
              developmentFundCouponAmount,
              expiresAt,
              reason,
            )
          },
        )(
          "A coupon is created and the total unclaimed development fund coupon amount decreases",
          _ => {
            aliceValidatorWalletClient
              .listActiveDevelopmentFundCoupons()
              .length shouldBe 1
            val expectedUnclaimedDevelopmentFundCouponTotal =
              initialUnclaimedDevelopmentFundCouponAmount - developmentFundCouponAmount
            getUnclaimedDevelopmentFundCouponTotal(
              sv1ScanBackend
            ) shouldBe expectedUnclaimedDevelopmentFundCouponTotal

            expectedUnclaimedDevelopmentFundCouponTotal
          },
        )
      }

      clue(
        "The coupon is expired"
      ) {
        eventually() {
          aliceValidatorWalletClient
            .listActiveDevelopmentFundCoupons() shouldBe empty
        }
      }

      clue(
        "The total unclaimed development fund coupon amount increases"
      ) {
        eventually() {
          getUnclaimedDevelopmentFundCouponTotal(
            sv1ScanBackend
          ) shouldBe (unclaimedDevelopmentFundCouponTotalAfterExpiration + developmentFundCouponAmount)
        }
      }

      clue("The expired coupon is listed in listDevelopmentFundCouponHistory as expired") {
        val expectedStatus = httpDef.ArchivedDevelopmentFundCouponStatus(
          httpDef.ArchivedDevelopmentFundCouponExpiredStatus(
            status = httpDef.ArchivedDevelopmentFundCouponExpiredStatus.Status.Expired
          )
        )
        aliceValidatorWalletClient
          .listDevelopmentFundCouponHistory(None, 10)
          .developmentFundCouponHistory
          .map(_.status) shouldBe Seq(expectedStatus)
      }
    }
  }

  "Listing active development fund coupons" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val developmentFundCouponExpirations = Seq(
      CantonTimestamp.now().plus(Duration.ofDays(3)),
      CantonTimestamp.now().plus(Duration.ofDays(2)),
      CantonTimestamp.now().plus(Duration.ofDays(5)),
      CantonTimestamp.now().plus(Duration.ofDays(1)),
      CantonTimestamp.now().plus(Duration.ofDays(2)),
    )
    val aliceValidatorParty = onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val fundManager = aliceValidatorParty
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val beneficiary = bobParty
    val developmentFundCouponAmount = BigDecimal(40.0)
    val reason = "Bob has contributed to the Daml repo"

    val bobUserName = bobWalletClient.config.ledgerApiUser
    val bobMergeAmuletsTrigger =
      bobValidatorBackend
        .userWalletAutomation(bobUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    setTriggersWithin(
      triggersToPauseAtStart = Seq(bobMergeAmuletsTrigger)
    ) {
      actAndCheck(
        "Mint some development fund coupons", {
          developmentFundCouponExpirations.foreach { expiresAt =>
            createDevelopmentFundCoupon(
              sv1ValidatorBackend.participantClientWithAdminToken,
              sv1UserId,
              beneficiary,
              fundManager,
              developmentFundCouponAmount,
              expiresAt,
              reason,
            )
          }
        },
      )(
        "Active coupons are listed with the earliest expiration date first",
        _ => {
          aliceValidatorWalletClient
            .listActiveDevelopmentFundCoupons()
            .map(_.payload.expiresAt)
            .toList shouldBe developmentFundCouponExpirations.map(_.toInstant).sorted
        },
      )
    }
  }

  "Listing history of development fund coupons" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val aliceValidatorParty = onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val fundManager = aliceValidatorParty
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val beneficiary = bobParty
    val amounts = Seq(10.0, 20.0, 30.0, 40.0, 50.0, 60.0)
    val expiresAt = CantonTimestamp.now().plus(Duration.ofDays(1))
    val reason = "Bob has contributed to the Daml repo"

    val bobUserName = bobWalletClient.config.ledgerApiUser
    val bobMergeAmuletsTrigger =
      bobValidatorBackend
        .userWalletAutomation(bobUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    setTriggersWithin(
      triggersToPauseAtStart = Seq(bobMergeAmuletsTrigger)
    ) {
      val (_, developmentFundCouponCidsSortedByAmount) = actAndCheck(
        "Mint some development fund coupons", {
          amounts.foreach { amount =>
            createDevelopmentFundCoupon(
              sv1ValidatorBackend.participantClientWithAdminToken,
              sv1UserId,
              beneficiary,
              fundManager,
              amount,
              expiresAt,
              reason,
            )
          }
        },
      )(
        "Coupons are created",
        _ => {
          val developmentFundCoupons = aliceValidatorWalletClient
            .listActiveDevelopmentFundCoupons()
          developmentFundCoupons.length shouldBe 6

          developmentFundCoupons.sortBy(_.payload.amount).map(_.contractId)
        },
      )

      // Note: withdrawal is used to reduce the chance of identical archival timestamps.
      // Other archival flows (e.g. expiration or claiming) are covered by separate tests
      // in this test suite. This test focuses on pagination and ordering by archival time.
      val withdrawalReason = "Bob's PR in the Daml repo broke CI"
      actAndCheck(
        "As the fund manager, Alice withdraws the five smallest development fund coupons", {
          developmentFundCouponCidsSortedByAmount.toList.init.foreach { cid =>
            aliceValidatorWalletClient
              .withdrawDevelopmentFundCoupon(cid, withdrawalReason)
          }
        },
      )(
        "The coupons are withdrawn and appear in listDevelopmentFundCouponHistory in the correct order",
        _ => {
          // Verify that five coupons have been archived
          aliceValidatorWalletClient.listActiveDevelopmentFundCoupons().length shouldBe 1

          val limit = 2
          val pages =
            LazyList
              .unfold(Option.empty[Long]) { tokenOpt =>
                val page =
                  aliceValidatorWalletClient.listDevelopmentFundCouponHistory(tokenOpt, limit)
                if (page.developmentFundCouponHistory.isEmpty) None
                else Some((page, page.nextPageToken))
              }
              .toList

          // Verify that coupons are sorted by archival time descending
          val history = pages.flatMap(_.developmentFundCouponHistory)
          history.length shouldBe 5
          pages.map(_.developmentFundCouponHistory.length) shouldBe List(2, 2, 1)
          history shouldBe history
            .sortBy(_.archivedAt)
            .reverse

          // Verify data
          val expectedAmounts = amounts.sorted.init
          history.map(_.amount.toDouble).sorted shouldBe expectedAmounts
          val expectedStatus = httpDef.ArchivedDevelopmentFundCouponStatus(
            httpDef.ArchivedDevelopmentFundCouponWithdrawnStatus(
              status = httpDef.ArchivedDevelopmentFundCouponWithdrawnStatus.Status.Withdrawn,
              reason = withdrawalReason,
            )
          )
          history.foreach { coupon =>
            coupon.fundManager shouldBe fundManager.toProtoPrimitive
            coupon.beneficiary shouldBe beneficiary.toProtoPrimitive
            coupon.reason shouldBe reason
            coupon.status shouldBe expectedStatus
          }
        },
      )
    }
  }

  private def assertUnclaimedDevelopmentCouponAmounts(
      expectedAmounts: Seq[BigDecimal]
  )(implicit env: FixtureParam) =
    sv1ScanBackend
      .listUnclaimedDevelopmentFundCoupons()
      .map(co => BigDecimal(co.payload.amount))
      .sorted shouldBe expectedAmounts

  private def getUnclaimedDevelopmentFundCouponTotal(scanAppRef: ScanAppBackendReference) =
    scanAppRef
      .listUnclaimedDevelopmentFundCoupons()
      .map(co => BigDecimal(co.contract.payload.amount))
      .sum

}
