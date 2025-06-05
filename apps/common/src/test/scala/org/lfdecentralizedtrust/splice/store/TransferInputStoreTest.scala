package org.lfdecentralizedtrust.splice.store

import com.daml.ledger.javaapi.data.Transaction
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  round as roundCodegen,
}

import scala.concurrent.Future

abstract class TransferInputStoreTest extends StoreTest {

  "listSortedAmuletsAndQuantity" should {
    "return correct results" in {
      for {
        store <- mkTransferInputStore(user)
        _ <- dummyDomain.ingest(mintTransaction(user, 11.0, 1L, 1.0))(store.multiDomainAcsStore)
        _ <- dummyDomain.ingest(mintTransaction(user, 12.0, 2L, 2.0))(store.multiDomainAcsStore)
        _ <- dummyDomain.ingest(mintTransaction(user, 13.0, 3L, 4.0))(store.multiDomainAcsStore)
        _ <- dummyDomain.ingest(mintTransaction(user, 10.0, 4L, 1.0))(store.multiDomainAcsStore)
      } yield {
        def top3At(round: Long): Seq[Double] =
          store
            .listSortedAmuletsAndQuantity(round, PageLimit.tryCreate(3))
            .futureValue
            .map(_._1.toDouble)

        // Values of the 4 amulets by time:
        // 11 10 09 08 07 06 05 04 03 02
        //    12 10 08 06 04 02 00 00 00
        //       13 09 05 01 00 00 00 00
        //          10 09 08 07 06 05 04
        // Note: need to start at round 4, as listSortedAmuletsAndQuantity() does not filter out amulets
        // created after the given round
        top3At(4L) should contain theSameElementsInOrderAs Seq(10.0, 9.0, 8.0)
        top3At(5L) should contain theSameElementsInOrderAs Seq(9.0, 7.0, 6.0)
        top3At(6L) should contain theSameElementsInOrderAs Seq(8.0, 6.0, 4.0)
        top3At(7L) should contain theSameElementsInOrderAs Seq(7.0, 5.0, 2.0)
        top3At(8L) should contain theSameElementsInOrderAs Seq(6.0, 4.0)
      }
    }
  }

  "listSortedValidatorRewards" should {
    "return correct results" in {
      for {
        store <- mkTransferInputStore(user)
        _ <- MonadUtil.sequentialTraverse(1 to 4)(n =>
          dummyDomain.create(
            validatorRewardCoupon(round = n, user = user, amount = numeric(n)),
            createdEventSignatories = Seq(dsoParty),
            createdEventObservers = Seq(user),
          )(store.multiDomainAcsStore)
        )
      } yield {
        def rewardAmounts(roundsO: Option[Seq[Long]]) =
          store
            .listSortedValidatorRewards(roundsO.map(_.toSet))
            .futureValue
            .map(_.payload.amount.doubleValue())

        rewardAmounts(None) should contain theSameElementsInOrderAs Seq(
          1.0,
          2.0,
          3.0,
          4.0,
        ) // without rounds filter
        rewardAmounts(Some(Seq(2, 3, 4))) should contain theSameElementsInOrderAs Seq(
          2.0,
          3.0,
          4.0,
        ) // with rounds filter
      }
    }
  }

  "listSortedAppRewards" should {
    "return correct results" in {
      for {
        store <- mkTransferInputStore(user)
        // for each round i, create 2 app reward coupons with amount i and 2i
        _ <- MonadUtil.sequentialTraverse(1 to 4)(n =>
          for {
            _ <- dummyDomain.create(
              appRewardCoupon(round = n, provider = user, amount = numeric(n)),
              createdEventSignatories = Seq(dsoParty),
              createdEventObservers = Seq(user),
            )(store.multiDomainAcsStore)
            _ <- dummyDomain.create(
              appRewardCoupon(round = n, provider = user, amount = numeric(2 * n)),
              createdEventSignatories = Seq(dsoParty),
              createdEventObservers = Seq(user),
            )(store.multiDomainAcsStore)
          } yield ()
        )
      } yield {
        store.listSortedAppRewards(Map.empty).futureValue shouldBe empty
        val roundsToFilter = (2 to 4).map(n => issuingMiningRound(dsoParty, n.toLong))
        // reward coupons are listed in ascending order of rounds and for each round in descending order of amounts
        store
          .listSortedAppRewards(roundsToFilter.map(r => r.payload.round -> r.payload).toMap)
          .futureValue
          .map(_._1.payload.amount.doubleValue()) should contain theSameElementsInOrderAs Seq(
          4.0, 2.0, // round 2
          6.0, 3.0, // round 3
          8.0, 4.0, // round 4
        )
      }
    }
  }

  /** A AmuletRules_Mint exercise event with one child Amulet create event */
  protected def mintTransaction(
      receiver: PartyId,
      amount: Double,
      round: Long,
      ratePerRound: Double,
      amuletPrice: Double = 1.0,
  )(
      offset: Long
  ): Transaction = {
    val amuletContract = amulet(receiver, amount, round, ratePerRound)

    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val amuletRulesCid = nextCid()
    val openMiningRoundCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        amuletRulesCid,
        amuletrulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        amuletrulesCodegen.AmuletRules.CHOICE_AmuletRules_Mint.name,
        consuming = false,
        new amuletrulesCodegen.AmuletRules_Mint(
          receiver.toProtoPrimitive,
          amuletContract.payload.amount.initialAmount,
          new roundCodegen.OpenMiningRound.ContractId(openMiningRoundCid),
        ).toValue,
        new amuletrulesCodegen.AmuletRules_MintResult(
          new amuletCodegen.AmuletCreateSummary[amuletCodegen.Amulet.ContractId](
            amuletContract.contractId,
            new java.math.BigDecimal(amuletPrice),
            new Round(round),
          )
        ).toValue,
      ),
      Seq(toCreatedEvent(amuletContract, Seq(receiver))),
      dummyDomain,
    )
  }

  protected def mkTransferInputStore(partyId: PartyId): Future[TransferInputStore]
  private lazy val user = userParty(1)
//  private lazy val user2 = userParty(2)
//  private lazy val validator = mkPartyId("validator")

}

object TransferInputStoreTest {}
