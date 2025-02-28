package org.lfdecentralizedtrust.splice.store.db

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.{DamlRecord, Unit as damlUnit}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  Amulet_ExpireResult,
  LockedAmulet_ExpireAmuletResult,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules_BuyMemberTrafficResult,
  AmuletRules_MintResult,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.FaucetState
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  round as roundCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsEntry
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  cometbft as cometbftCodegen,
  dsorules as dsorulesCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer as decentralizedsynchronizerCodegen
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{DsoRules, Reason, Vote}
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.history.{
  AmuletExpire,
  ExternalPartyAmuletRules_CreateTransferCommand,
  LockedAmuletExpireAmulet,
  Transfer,
  TransferCommand_Expire,
  TransferCommand_Send,
  TransferCommand_Withdraw,
}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.store.{
  OpenMiningRoundTxLogEntry,
  ReceiverAmount,
  SenderAmount,
  TransferCommandCreated,
  TransferCommandExpired,
  TransferCommandFailed,
  TransferCommandSent,
  TransferCommandTxLogEntry,
  TransferCommandWithdrawn,
  TransferTxLogEntry,
}
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbScanStore,
  DbScanStoreMetrics,
  ScanAggregatesReader,
  ScanAggregator,
}
import org.lfdecentralizedtrust.splice.store.{PageLimit, SortOrder, StoreErrors, StoreTest}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState.Assigned
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.util.SpliceUtil.damlDecimal
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  ContractWithState,
  EventId,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}

import java.time.Instant
import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import scala.reflect.ClassTag
import com.digitalasset.canton.util.MonadUtil

import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext

abstract class ScanStoreTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with AmuletTransferUtil {

  "ScanStore" should {

    "getTotalAmuletBalance" should {

      "return correct total amulet balance for the round where the transfer happened and for the rounds before and after" in {
        val amuletAmount = 100.0
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }
        for {
          store <- mkStore()
          amuletRulesContract = amuletRules()
          _ <- dummyDomain.exercise(
            amuletRulesContract,
            interfaceId = Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
            Transfer.choice.name,
            mkAmuletRulesTransfer(user1, amuletAmount),
            mkTransferResultRecord(
              round = 2,
              inputAppRewardAmount = 0,
              inputAmuletAmount = amuletAmount,
              inputValidatorRewardAmount = 0,
              inputSvRewardAmount = 0,
              balanceChanges = Map(
                user1.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
                  BigDecimal(amuletAmount).bigDecimal,
                  holdingFee.bigDecimal,
                )
              ),
              amuletPrice = 0.0005,
            ),
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalAmuletBalance(1).futureValue shouldBe (0.0)
          // 100.0 is the initial amount as of round 0, so at the end of round 2 the holding fee was applied three times
          store.getTotalAmuletBalance(2).futureValue shouldBe (amuletAmount - 3 * holdingFee)
          store.getTotalAmuletBalance(3).futureValue shouldBe (amuletAmount - 4 * holdingFee)
        }
      }

      "return correct total amulet balance for the round where the amulet expired and for the rounds before and after" in {
        val amuletRound1 = 100.0
        val changeToInitialAmountAsOfRoundZero = -50.0
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- dummyDomain.ingest(mintTransaction(user1, amuletRound1, 1, holdingFee))(
            store.multiDomainAcsStore
          )
          amuletContract = amulet(user1, amuletRound1, 1, holdingFee)
          _ <- dummyDomain.exercise(
            amuletContract,
            interfaceId = Some(splice.amulet.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID),
            AmuletExpire.choice.name,
            mkAmuletExpire(),
            mkAmuletExpireResult(
              user1,
              2,
              changeToInitialAmountAsOfRoundZero,
              holdingFee,
            ),
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalAmuletBalance(1).futureValue shouldBe (amuletRound1 - 1 * holdingFee)
          store
            .getTotalAmuletBalance(2)
            .futureValue shouldBe (amuletRound1 - 2 * holdingFee + changeToInitialAmountAsOfRoundZero - 3 * holdingFee)
          store
            .getTotalAmuletBalance(3)
            .futureValue shouldBe (amuletRound1 - 3 * holdingFee + changeToInitialAmountAsOfRoundZero - 4 * holdingFee)
        }
      }

      "return correct total amulet balance for the round where the locked amulet expired and for the rounds before and after" in {
        val amuletRound1 = 100.0
        val changeToInitialAmountAsOfRoundZero = BigDecimal(-50.0)
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- dummyDomain.ingest(mintTransaction(user1, amuletRound1, 1, holdingFee))(
            store.multiDomainAcsStore
          )
          amuletContract = lockedAmulet(user1, amuletRound1, 1, holdingFee)
          _ <- dummyDomain.exercise(
            amuletContract,
            interfaceId = Some(splice.amulet.LockedAmulet.TEMPLATE_ID_WITH_PACKAGE_ID),
            LockedAmuletExpireAmulet.choice.name,
            mkLockedAmuletExpireAmulet(),
            new LockedAmulet_ExpireAmuletResult(
              new splice.amulet.AmuletExpireSummary(
                user1.toProtoPrimitive,
                new splice.types.Round(2),
                changeToInitialAmountAsOfRoundZero.bigDecimal,
                holdingFee.bigDecimal,
              )
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalAmuletBalance(1).futureValue shouldBe (amuletRound1 - 1 * holdingFee)
          store
            .getTotalAmuletBalance(2)
            .futureValue shouldBe (amuletRound1 - 2 * holdingFee + changeToInitialAmountAsOfRoundZero - 3 * holdingFee)
          store
            .getTotalAmuletBalance(3)
            .futureValue shouldBe (amuletRound1 - 3 * holdingFee + changeToInitialAmountAsOfRoundZero - 4 * holdingFee)
        }
      }

      "return correct total amulet balance for the round where the mint happened and for the rounds before and after" in {
        val mintAmount = 100.0
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- dummyDomain.ingest(mintTransaction(user1, mintAmount, 2, holdingFee))(
            store.multiDomainAcsStore
          )
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalAmuletBalance(1).futureValue shouldBe (0.0)
          // The amulet is minted at round 2, so at the end of that round it's already incurring 1 x holding fee
          store.getTotalAmuletBalance(2).futureValue shouldBe (mintAmount - 1 * holdingFee)
          store.getTotalAmuletBalance(3).futureValue shouldBe (mintAmount - 2 * holdingFee)
        }
      }

    }

    "getWalletBalance" should {
      "return correct wallet balance for the round where the transfer happened and for the rounds before and after" in {
        val keptAmuletAmount = 60.0
        val sentAmuletAmount = 40.0
        val amuletAmount = keptAmuletAmount + sentAmuletAmount
        val lastClosedRound = 5
        val balanceChanges = Seq(
          // round 0: no change
          // round 1: user1 gets balance increase, no change for user2
          1L -> Map(
            user1.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              BigDecimal(10.0).bigDecimal,
              holdingFee.bigDecimal,
            )
          ),
          // round 2: both users get a balance increase
          2L -> Map(
            user1.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              BigDecimal(60.0).bigDecimal,
              holdingFee.bigDecimal,
            ),
            user2.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              BigDecimal(40.0).bigDecimal,
              (2 * holdingFee).bigDecimal,
            ),
          ),
          // round 3: user1 reduces balance, no change for user2
          3L -> Map(
            user1.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              BigDecimal(-40.0).bigDecimal,
              (-holdingFee).bigDecimal,
            )
          ),
          // round 4: user2 reduces balance, no change for user1
          4L -> Map(
            user2.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              BigDecimal(-30.0).bigDecimal,
              (-holdingFee).bigDecimal,
            )
          ),
          // round 5: both users increase their balance
          5L -> Map(
            user1.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              BigDecimal(10.0).bigDecimal,
              holdingFee.bigDecimal,
            ),
            user2.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              BigDecimal(10.0).bigDecimal,
              holdingFee.bigDecimal,
            ),
          ),
        )
        // We only care about the balance changes in the result, just add a random dummy argument.
        val dummyTransferArg = mkAmuletRulesTransfer(user1, 0.0)
        for {
          store <- mkStore()
          // Close the first 2 rounds, no events for them.
          _ <- Seq(0L, 1L).traverse { round =>
            for {
              _ <- dummyDomain.create(
                closedMiningRound(dsoParty, round)
              )(store.multiDomainAcsStore)
              _ <- store.aggregate()
            } yield ()
          }
          amuletRulesContract = amuletRules()
          _ <- balanceChanges.traverse { case (round, balanceChanges) =>
            for {
              _ <- dummyDomain.exercise(
                amuletRulesContract,
                interfaceId = Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
                Transfer.choice.name,
                dummyTransferArg,
                mkTransferResultRecord(
                  round = round,
                  inputAppRewardAmount = 0,
                  inputAmuletAmount = amuletAmount,
                  inputValidatorRewardAmount = 0,
                  inputSvRewardAmount = 0,
                  balanceChanges = balanceChanges,
                  amuletPrice = 0.0005,
                ),
                nextOffset(),
              )(
                store.multiDomainAcsStore
              )
              _ <- dummyDomain.create(
                closedMiningRound(dsoParty, round)
              )(store.multiDomainAcsStore)
              _ <- store.aggregate()
            } yield ()
          }
        } yield {
          // 100.0 is the initial amount as of round 0, so at the end of round 2 the holding fee was applied three times
          forEvery(
            Table(
              ("user", "round", "initial amulet amount", "holding fee rate"),
              (user1, 0L, BigDecimal(0.0), BigDecimal(0.0)),
              (user2, 0L, BigDecimal(0.0), BigDecimal(0.0)),
              (user1, 1L, BigDecimal(10.0), holdingFee),
              (user2, 1L, BigDecimal(0.0), BigDecimal(0.0)),
              (user1, 2L, BigDecimal(10.0 + 60.0), 2 * holdingFee),
              (user2, 2L, BigDecimal(40.0), 2 * holdingFee),
              (user1, 3L, BigDecimal(10.0 + 60.0 - 40.0), holdingFee),
              (user2, 3L, BigDecimal(40.0), 2 * holdingFee),
              (user1, 4L, BigDecimal(10.0 + 60.0 - 40.0), holdingFee),
              (user2, 4L, BigDecimal(40.0 - 30.0), holdingFee),
              (user1, 5L, BigDecimal(10.0 + 60.0 - 40.0 + 10), 2 * holdingFee),
              (user2, 5L, BigDecimal(40.0 - 30.0 + 10), 2 * holdingFee),
            )
          ) { (user, round, initialAmuletAmount, holdingFeeRate) =>
            store
              .getWalletBalance(user, round)
              .futureValue shouldBe initialAmuletAmount - BigDecimal(round + 1) * holdingFeeRate
          }

          forAll(Seq(user1, user2)) { user =>
            val failure = store.getWalletBalance(user, lastClosedRound + 1L).failed.futureValue
            failure.getMessage should be(roundNotAggregated().getMessage)
          }
        }
      }

      "accumulate on amulet expiry, locked amulet expiry, and minting" in {
        import MonadUtil.sequentialTraverse_
        val mintAmount1 = 60.0
        val mintAmount2 = 40.0
        val expireAmount1 = -24.0
        val expireAmount2 = -18.0
        for {
          store <- mkStore()
          _ = amuletRules()
          // the round where the mint happened and for the rounds before and after
          _ <- sequentialTraverse_(Seq(user1 -> mintAmount1, user2 -> mintAmount2)) {
            case (user, mintAmount) =>
              dummyDomain.ingest(
                mintTransaction(user, mintAmount, 2, holdingFee)
              )(store.multiDomainAcsStore)
          }
          // "the round where the amulet expired and for the rounds before and after"
          amuletContract = amulet(user1, mintAmount1, 2, holdingFee)
          _ <- dummyDomain.exercise(
            amuletContract,
            interfaceId = Some(splice.amulet.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID),
            AmuletExpire.choice.name,
            mkAmuletExpire(),
            mkAmuletExpireResult(
              user1,
              4,
              expireAmount1,
              holdingFee,
            ),
            nextOffset(),
          )(store.multiDomainAcsStore)
          // "the round where the locked amulet expired and for the rounds before and after
          lockedAmuletContract = lockedAmulet(user2, mintAmount2, 2, holdingFee)
          _ <- dummyDomain.exercise(
            lockedAmuletContract,
            interfaceId = Some(splice.amulet.LockedAmulet.TEMPLATE_ID_WITH_PACKAGE_ID),
            LockedAmuletExpireAmulet.choice.name,
            mkLockedAmuletExpireAmulet(),
            mkAmuletExpireResult(
              user2,
              6,
              expireAmount2,
              holdingFee,
            ),
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          closedRounds = (0 to 7).map { round =>
            closedMiningRound(dsoParty, round = round.toLong)
          }
          _ <- MonadUtil.sequentialTraverse(closedRounds) { closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore)
          }
          _ <- store.aggregate()
        } yield forEvery(
          Table(
            ("user", "round", "amulet amount"),
            (user1, 1L, BigDecimal(0)),
            (user2, 1L, BigDecimal(0)),
            // check mints
            (user1, 2L, mintAmount1 - 1 * holdingFee),
            (user2, 2L, mintAmount2 - 1 * holdingFee),
            (user1, 3L, mintAmount1 - 2 * holdingFee),
            (user2, 3L, mintAmount2 - 2 * holdingFee),
            // check expire user1
            (user1, 4L, mintAmount1 - 3 * holdingFee + expireAmount1 - 5 * holdingFee),
            (user2, 4L, mintAmount2 - 3 * holdingFee),
            (user1, 5L, mintAmount1 - 4 * holdingFee + expireAmount1 - 6 * holdingFee),
            (user2, 5L, mintAmount2 - 4 * holdingFee),
            // check locked expire user2
            (user1, 6L, mintAmount1 - 5 * holdingFee + expireAmount1 - 7 * holdingFee),
            (user2, 6L, mintAmount2 - 5 * holdingFee + expireAmount2 - 7 * holdingFee),
            (user1, 7L, mintAmount1 - 6 * holdingFee + expireAmount1 - 8 * holdingFee),
            (user2, 7L, mintAmount2 - 6 * holdingFee + expireAmount2 - 8 * holdingFee),
          )
        ) { (user, round, amuletAmount) =>
          store.getWalletBalance(user, round).futureValue shouldBe amuletAmount
        }
      }
    }

    "getTotalRewardsCollectedEver" should {

      "return the sum of reward amounts (ValidatorReward & AppReward)" in {
        val validatorRewards = Seq(
          9.5,
          11.5,
        )
        val appRewards = Seq(
          11.25,
          9.75,
        )
        val closedRounds = (0 to 1).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(appRewards.zip(validatorRewards).zipWithIndex) {
            case ((appAmount, validatorAmount), round) =>
              dummyDomain.exercise(
                amuletRules(),
                Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
                Transfer.choice.name,
                mkAmuletRulesTransfer(user1, 1.0),
                mkTransferResultRecord(
                  round = round.toLong,
                  inputAppRewardAmount = appAmount,
                  inputValidatorRewardAmount = validatorAmount,
                  inputSvRewardAmount = 0,
                  inputAmuletAmount = 0,
                  balanceChanges = Map(),
                  amuletPrice = 0.0005,
                ),
              )(store.multiDomainAcsStore)
          }
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store
            .getTotalRewardsCollectedEver()
            .futureValue shouldBe validatorRewards.sum + appRewards.sum
        }
      }

    }

    "getRewardsCollectedInRound" should {

      "return the sum of reward amounts (ValidatorReward & AppReward) up to the round" in {
        val validatorRewards = Seq(
          9.5,
          11.5,
          33.3,
        )
        val appRewards = Seq(
          11.25,
          9.75,
          33.3,
        )
        val closedRounds = (0 to 2).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(appRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              amuletRules(),
              Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              Transfer.choice.name,
              mkAmuletRulesTransfer(user1, amount),
              mkTransferResultRecord(
                round = round.toLong,
                inputAppRewardAmount = amount,
                inputAmuletAmount = 0,
                inputValidatorRewardAmount = 0,
                inputSvRewardAmount = 0,
                balanceChanges = Map(),
                amuletPrice = 0.0005,
              ),
            )(store.multiDomainAcsStore)
          }
          _ <- MonadUtil.sequentialTraverse(validatorRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              amuletRules(),
              Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              Transfer.choice.name,
              mkAmuletRulesTransfer(user1, amount),
              mkTransferResultRecord(
                round = round.toLong,
                inputAppRewardAmount = 0,
                inputValidatorRewardAmount = amount,
                inputSvRewardAmount = 0,
                inputAmuletAmount = 0,
                balanceChanges = Map(),
                amuletPrice = 0.0005,
              ),
            )(store.multiDomainAcsStore)
          }
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getRewardsCollectedInRound(1).futureValue shouldBe validatorRewards(
            1
          ) + appRewards(1)
        }
      }

    }

    "getAmuletConfigForRound" should {

      "return the amulet OpenMiningRoundTxLogEntry for the round" in {
        val wanted = openMiningRound(dsoParty, round = 2, amuletPrice = 2.0)
        val unwanted = openMiningRound(dsoParty, round = 3, amuletPrice = 3.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          val logEntry = store.getAmuletConfigForRound(round = 2).futureValue
          logEntry match {
            case omr: OpenMiningRoundTxLogEntry =>
              omr.round should be(wanted.payload.round.number)
            case x =>
              fail(s"Entry was not an OpenMiningRoundTxLogEntry but a $x")
          }
          numeric(logEntry.amuletCreateFee) should be(
            numeric(
              wanted.payload.transferConfigUsd.createFee.fee.divide(wanted.payload.amuletPrice)
            )
          )
        }
      }

    }

    "getRoundOfLatestData" should {

      "return the latest closed round" in {
        val closedBefore = (0 until 2).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }
        val closed = closedMiningRound(dsoParty, round = 2)
        for {
          store <- mkStore()
          closeTime = Instant.ofEpochSecond(1500)
          _ <- MonadUtil.sequentialTraverse(closedBefore) { closed =>
            dummyDomain.create(closed, txEffectiveAt = closeTime)(
              store.multiDomainAcsStore
            )
          }
          _ <- dummyDomain.create(closed, txEffectiveAt = closeTime)(
            store.multiDomainAcsStore
          )
          _ <- store.aggregate()
        } yield {
          val (round, effectiveAt) = store.getRoundOfLatestData().futureValue
          round should be(2)
          effectiveAt should be(closeTime)
        }
      }

      "fail if there's no closed round" in {
        val open = openMiningRound(dsoParty, round = 2, amuletPrice = 2.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(open)(store.multiDomainAcsStore)
        } yield {
          val failure = store.getRoundOfLatestData().failed.futureValue
          failure.getMessage should be(roundNotAggregated().getMessage)
        }
      }
    }

    // tests look the same for both getTopProvidersByAppRewards & getTopValidatorsByValidatorRewards
    type Round = Long
    type Limit = Int
    type Amount = Double
    def topProvidersTest(
        getTopProviders: (ScanStore, Round, Limit) => Future[Seq[(PartyId, BigDecimal)]],
        mkTransferResultForTest: (Amount, Round) => DamlRecord,
    ) = {
      val asOfEndOfRound = 5L
      val providerRewardRounds = Seq(
        // (provider, amount, round)
        // 1
        (userParty(1), 4.0, 1),
        (userParty(1), 4.0, 2),
        (userParty(1), 666.0, 200), // excluded
        // 2
        (userParty(2), 4.0, 1),
        // 3
        (userParty(3), 4.0, 1),
        (userParty(3), 4.0, 2),
        (userParty(3), 4.0, 3),
        // 4
        (userParty(4), 4.0, 10000), // excluded
      )
      val closed = closedMiningRound(dsoParty, round = asOfEndOfRound)
      val closedBefore = (0 until asOfEndOfRound.toInt).map { round =>
        closedMiningRound(dsoParty, round = round.toLong)
      }
      for {
        store <- mkStore()
        _ <- MonadUtil.sequentialTraverse(closedBefore) { closed =>
          dummyDomain.create(closed)(store.multiDomainAcsStore)
        }
        _ <- dummyDomain.create(closed)(store.multiDomainAcsStore)
        _ <- MonadUtil.sequentialTraverse(providerRewardRounds) { case (provider, amount, round) =>
          dummyDomain.exercise(
            amuletRules(),
            Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
            Transfer.choice.name,
            mkAmuletRulesTransfer(provider, amount),
            mkTransferResultForTest(
              amount,
              round.toLong,
            ),
          )(store.multiDomainAcsStore)
        }
        _ <- store.aggregate()
      } yield {
        getTopProviders(store, asOfEndOfRound, 2).futureValue shouldBe Seq(
          userParty(3) -> BigDecimal(4.0 * 3),
          userParty(1) -> BigDecimal(4.0 * 2),
        )
      }
    }

    "getTopProvidersByAppRewards" should {

      "return the top `limit` providers by app rewards" in {
        topProvidersTest(
          (store, round, limit) => store.getTopProvidersByAppRewards(round, limit),
          (amount, round) =>
            mkTransferResultRecord(
              round = round,
              inputAppRewardAmount = amount,
              inputAmuletAmount = 0,
              inputValidatorRewardAmount = 0,
              inputSvRewardAmount = 0,
              balanceChanges = Map(),
              amuletPrice = 0.0005,
            ),
        )
      }
    }

    "getTopValidatorsByValidatorRewards" should {

      "return the top `limit` providers by app rewards" in {
        topProvidersTest(
          (store, round, limit) => store.getTopValidatorsByValidatorRewards(round, limit),
          (amount, round) =>
            mkTransferResultRecord(
              round = round,
              inputAppRewardAmount = 0,
              inputValidatorRewardAmount = amount,
              inputAmuletAmount = 0,
              inputSvRewardAmount = 0,
              balanceChanges = Map(),
              amuletPrice = 0.0005,
            ),
        )
      }
    }

    "getTopValidatorsByPurchasedTraffic" should {

      "return the top `limit` providers by purchased traffic" in {
        val asOfEndOfRound = 5L
        val trafficPurchaseTrees = Seq(
          // user 1
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 2,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 3,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 2
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(2),
            memberId = mkParticipantId("user-2"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 3
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(3),
            memberId = mkParticipantId("user-3"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(3),
            memberId = mkParticipantId("user-3"),
            round = 2,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          // user 4
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(4),
            memberId = mkParticipantId("user-4"),
            round = 1000, // excluded
            extraTraffic = 400000,
            ccSpent = 2222.0,
          )(_),
        )
        val closedBefore = (0 until asOfEndOfRound.toInt).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }
        val closed = closedMiningRound(dsoParty, round = asOfEndOfRound)
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(closedBefore) { closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore)
          }
          _ <- dummyDomain.create(closed)(store.multiDomainAcsStore)
          _ <- MonadUtil.sequentialTraverse(trafficPurchaseTrees)(
            dummyDomain.ingest(_)(store.multiDomainAcsStore)
          )
          _ <- store.aggregate()
        } yield {
          store
            .getTopValidatorsByPurchasedTraffic(asOfEndOfRound, limit = 2)
            .futureValue shouldBe Seq(
            HttpScanAppClient.ValidatorPurchasedTraffic(
              validator = userParty(1),
              numPurchases = 3,
              totalTrafficPurchased = 4 * 3,
              totalCcSpent = 2.0 * 3,
              lastPurchasedInRound = 3,
            ),
            HttpScanAppClient.ValidatorPurchasedTraffic(
              validator = userParty(3),
              numPurchases = 2,
              totalTrafficPurchased = 4 * 2,
              totalCcSpent = 3.0 * 2,
              lastPurchasedInRound = 2,
            ),
          )
        }
      }

    }

    "getTotalPurchasedMemberTraffic" should {

      "return the sum over all traffic contracts for the member" in {
        val namespace = Namespace(Fingerprint.tryCreate(s"dummy"))
        val goodMember = ParticipantId(UniqueIdentifier.tryCreate("good", namespace))
        val badMember = MediatorId(UniqueIdentifier.tryCreate("bad", namespace))
        val goodContracts =
          (1 to 3).map(n => memberTraffic(goodMember, domainMigrationId, n.toLong))
        val badContracts =
          (4 to 6).map(n => memberTraffic(badMember, domainMigrationId, n.toLong)) ++
            (7 to 9).map(n => memberTraffic(goodMember, nextDomainMigrationId, n.toLong))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            goodContracts ++ badContracts
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.getTotalPurchasedMemberTraffic(
            goodMember,
            dummyDomain,
          )
        } yield result shouldBe (1 to 3).sum.toLong
      }

    }

    "lookupAmuletRules" should {

      "find the latest amulet rules" in {
        val cr = amuletRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupAmuletRules()
            .futureValue
            .map(_.contract) should be(Some(cr))
        }
      }

    }

    "lookupAnsRules" should {
      "find the latest ANS rules" in {
        val cr = ansRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupAnsRules()
            .futureValue
            .map(_.contract) should be(Some(cr))
        }
      }
    }

    "lookupDsoRules" should {
      "find the latest Dso rules" in {
        val sr = dsoRules(user1)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(sr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupDsoRules()
            .futureValue
            .map(_.contract) should be(Some(sr))
        }
      }
    }

    "findFeaturedAppRight" should {

      "return the FeaturedAppRight of the wanted provider" in {
        val wanted = featuredAppRight(userParty(1))
        val unwanted = featuredAppRight(userParty(2))
        val expectedResult = Some(ContractWithState(wanted, Assigned(dummyDomain)))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          store
            .findFeaturedAppRight(userParty(1))
            .futureValue should be(expectedResult)
        }
      }
    }

    "lookupTransferPreapprovalByParty" should {
      "return the TransferPreapproval contract signed by the specified party if available" in {
        val wanted = transferPreapproval(userParty(1), providerParty(1), time(0), time(1))
        val unwanted = transferPreapproval(userParty(2), providerParty(1), time(0), time(1))
        val expectedResult = Some(ContractWithState(wanted, Assigned(dummyDomain)))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          store.lookupTransferPreapprovalByParty(userParty(1)).futureValue should be(expectedResult)
          store.lookupTransferPreapprovalByParty(userParty(3)).futureValue should be(None)
        }
      }

      "return the latest created TransferPreapproval contract if there are multiple" in {
        val older =
          transferPreapproval(userParty(1), providerParty(1), validFrom = time(0), time(1))
        val newer =
          transferPreapproval(userParty(1), providerParty(2), validFrom = time(2), time(3))
        val expectedResult = Some(ContractWithState(newer, Assigned(dummyDomain)))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(older)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(newer)(store.multiDomainAcsStore)
        } yield {
          store.lookupTransferPreapprovalByParty(userParty(1)).futureValue should be(expectedResult)
        }
      }
    }

    "lookupTransferCommandCounterByParty" should {
      "return the TransferCommandCounter for the specified party if available" in {
        val counter = transferCommandCounter(userParty(1), 0L)
        for {
          store <- mkStore()
          r <- store.lookupTransferCommandCounterByParty(userParty(1))
          _ = r shouldBe None
          _ <- dummyDomain.create(counter)(store.multiDomainAcsStore)
          r <- store.lookupTransferCommandCounterByParty(userParty(1))
          _ = r.map(_.contract) shouldBe Some(counter)
          r <- store.lookupTransferCommandCounterByParty(userParty(2))
          _ = r shouldBe None
        } yield succeed
      }
    }

    val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    val timeInThePast = now.minusSeconds(3600)

    "listEntries" should {
      "list entries with prefix" in {
        for {
          store <- mkStore()
          unwantedContract = ansEntry(1, "unwanted")
          wantedContract = ansEntry(2, "wanted")
          wantedContract2 = ansEntry(3, "wanted2")
          expiredContract = ansEntry(4, "wanted3", timeInThePast)
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiredContract)(store.multiDomainAcsStore)
          expectedResult = Seq(
            ContractWithState(wantedContract, Assigned(dummyDomain)),
            ContractWithState(wantedContract2, Assigned(dummyDomain)),
          )
        } yield {
          store
            .listEntries("wanted", CantonTimestamp.assertFromInstant(now))
            .futureValue should be(
            expectedResult
          )
          store.listEntries("dummy", CantonTimestamp.assertFromInstant(now)).futureValue should be(
            Seq.empty
          )
        }
      }
    }

    "lookupEntryByName" should {
      "return None for no entry" in {
        for {
          store <- mkStore()
          result <- store.lookupEntryByName("nope", CantonTimestamp.assertFromInstant(now))
        } yield result should be(None)
      }

      "return the entry with the exact name" in {
        for {
          store <- mkStore()
          unwantedContract = ansEntry(1, "unwanted")
          expiredContract = ansEntry(2, "wanted", timeInThePast)
          wantedContract = ansEntry(3, "wanted")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiredContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupEntryByName(
              "wanted",
              CantonTimestamp.assertFromInstant(timeInThePast.minusSeconds(10)),
            )
            .futureValue should be(
            Some(ContractWithState(expiredContract, Assigned(dummyDomain)))
          )
          store
            .lookupEntryByName("wanted", CantonTimestamp.assertFromInstant(now))
            .futureValue should be(
            Some(ContractWithState(wantedContract, Assigned(dummyDomain)))
          )
        }
      }
    }

    "lookupEntryByParty" should {
      "return the first lexicographical entry of the user" in {
        for {
          store <- mkStore()
          unwantedContract = ansEntry(1, "unwanted")
          expiredContract = ansEntry(2, "expired", timeInThePast)
          bContract = ansEntry(2, "b")
          aContract = ansEntry(2, "a")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiredContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(bContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(aContract)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupEntryByParty(
              userParty(2),
              CantonTimestamp.assertFromInstant(timeInThePast.minusSeconds(10)),
            )
            .futureValue should be(Some(ContractWithState(aContract, Assigned(dummyDomain))))
          store
            .lookupEntryByParty(userParty(2), CantonTimestamp.assertFromInstant(now))
            .futureValue should be(Some(ContractWithState(aContract, Assigned(dummyDomain))))
        }
      }
    }

    "listTransactions" should {
      "return the most recent txs in pages" in {
        val limit = 10
        val nrTransfers = 20
        val round = 1L
        val now = java.time.Instant.EPOCH
        val zero = BigDecimal(0)
        val fakeOffset = "0"
        val txs: List[TransferTxLogEntry] = (1 to nrTransfers).map { i =>
          TransferTxLogEntry(
            offset = fakeOffset,
            eventId = s"$i",
            domainId = dummyDomain,
            date = Some(now),
            provider = user1,
            sender = Some(
              SenderAmount(
                user1,
                BigDecimal(i),
                zero,
                zero,
                zero,
                zero,
                zero,
                zero,
                Some(zero),
                None,
              )
            ),
            balanceChanges = Seq(),
            receivers = Seq(ReceiverAmount(user2, BigDecimal(i), zero)),
            round = round,
            amuletPrice = BigDecimal(1.0),
          )
        }.toList
        def stripEventIdAndOffset(tx: TransferTxLogEntry) =
          tx.copy(eventId = "", offset = fakeOffset)
        val expectedFirstPage = txs.reverse.take(limit).toList
        val expectedSecondPage = txs.reverse.drop(limit).take(limit).toList

        def transferFromTransaction(
            store: ScanStore,
            amuletRulesContract: Contract[
              splice.amuletrules.AmuletRules.ContractId,
              splice.amuletrules.AmuletRules,
            ],
            tx: TransferTxLogEntry,
        ) = {
          val sender = tx.sender.getOrElse(throw txMissingField())
          val senderParty = sender.party
          val senderAmount = sender.inputAmuletAmount
          val receiverParty = tx.receivers(0).party
          val receiverAmount = tx.receivers(0).amount
          dummyDomain
            .exercise(
              contract = amuletRulesContract,
              interfaceId = Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = Transfer.choice.name,
              choiceArgument = mkAmuletRules_Transfer(
                mkTransferInputOutput(
                  senderParty,
                  senderParty,
                  List(mkInputAmulet()),
                  List(mkTransferOutput(receiverParty, receiverAmount)),
                )
              ),
              exerciseResult = mkTransferResultRecord(
                round = round,
                inputAppRewardAmount = sender.inputAppRewardAmount.toDouble,
                inputAmuletAmount = senderAmount.toDouble,
                inputValidatorRewardAmount = sender.inputValidatorRewardAmount.toDouble,
                inputSvRewardAmount = sender.inputSvRewardAmount.fold(0.0)(_.toDouble),
                balanceChanges = Map(),
                amuletPrice = tx.amuletPrice.toDouble,
              ),
            )(
              store.multiDomainAcsStore
            )
            .map(_ => ())
        }

        for {
          store <- mkStore()
          amuletRulesContract = amuletRules()
          _ <- txs.foldLeft(Future.successful(())) { (f, tx) =>
            f.flatMap { _ =>
              transferFromTransaction(
                store,
                amuletRulesContract,
                tx,
              )
            }
          }
        } yield {
          val firstPageDescending = store
            .listByType[TransferTxLogEntry](None, SortOrder.Descending, limit)
            .futureValue
            .toList

          firstPageDescending
            .map(stripEventIdAndOffset) should be(
            expectedFirstPage
              .map(stripEventIdAndOffset)
          )
          val nextPageDescending = store
            .listByType[TransferTxLogEntry](
              Some(firstPageDescending.last.eventId),
              SortOrder.Descending,
              limit,
            )
            .futureValue
            .toList

          nextPageDescending
            .map(stripEventIdAndOffset) should be(
            expectedSecondPage
              .map(stripEventIdAndOffset)
          )

          val firstPageAscending = store
            .listByType[TransferTxLogEntry](None, SortOrder.Ascending, limit)
            .futureValue
            .toList

          firstPageAscending should be(nextPageDescending.reverse)

          val nextPageAscending = store
            .listByType[TransferTxLogEntry](
              Some(firstPageAscending.last.eventId),
              SortOrder.Ascending,
              limit,
            )
            .futureValue
            .toList

          nextPageAscending should be(firstPageDescending.reverse)
        }
      }
    }

    "votes" should {

      "listVoteRequestResults" should {

        "list all past VoteRequestResult" in {
          for {
            store <- mkStore()
            voteRequestContract = voteRequest(
              requester = userParty(1),
              votes = (1 to 4)
                .map(n => new Vote(userParty(n).toProtoPrimitive, true, new Reason("", ""))),
            )
            _ <- dummyDomain.create(voteRequestContract)(store.multiDomainAcsStore)
            result = mkVoteRequestResult(voteRequestContract)
            _ <- dummyDomain.exercise(
              contract = dsoRules(party = dsoParty),
              interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = DsoRulesCloseVoteRequest.choice.name,
              mkCloseVoteRequest(
                voteRequestContract.contractId
              ),
              result.toValue,
            )(
              store.multiDomainAcsStore
            )
          } yield {
            store
              .listVoteRequestResults(
                Some("AddSv"),
                Some(true),
                None,
                None,
                None,
                PageLimit.tryCreate(1),
              )
              .futureValue
              .toList
              .loneElement shouldBe result
            store
              .listVoteRequestResults(
                Some("SRARC_AddSv"),
                Some(false),
                None,
                None,
                None,
                PageLimit.tryCreate(1),
              )
              .futureValue
              .toList
              .size shouldBe (0)
            store
              .listVoteRequestResults(
                None,
                None,
                None,
                None,
                None,
                PageLimit.tryCreate(1),
              )
              .futureValue
              .toList
              .size shouldBe (1)
            store
              .listVoteRequestResults(
                None,
                None,
                None,
                Some(Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600).toString),
                None,
                PageLimit.tryCreate(1),
              )
              .futureValue
              .toList
              .size shouldBe (0)
            store
              .listVoteRequestResults(
                None,
                None,
                None,
                Some(Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(3600).toString),
                None,
                PageLimit.tryCreate(1),
              )
              .futureValue
              .toList
              .size shouldBe (1)
          }
        }
      }

      "listVoteRequestsByTrackingCid" should {

        "return all votes by their VoteRequest contract ids" in {
          val goodVotes = (1 to 3).map(n =>
            Seq(n, n + 3)
              .map(i => new Vote(userParty(i).toProtoPrimitive, true, new Reason("", "")))
          )
          val badVotes = (1 to 3).map(n =>
            Seq(n)
              .map(i => new Vote(userParty(i).toProtoPrimitive, true, new Reason("", "")))
          )
          val goodVoteRequests =
            (1 to 3).map(n =>
              voteRequest(
                requester = userParty(n),
                votes = goodVotes(n - 1),
              )
            )
          val badVoteRequests =
            (4 to 6).map(n => voteRequest(requester = userParty(n), votes = badVotes(n - 4)))
          for {
            store <- mkStore()
            _ <- MonadUtil.sequentialTraverse(goodVoteRequests ++ badVoteRequests)(
              dummyDomain.create(_)(store.multiDomainAcsStore)
            )
            result <- store.listVoteRequestsByTrackingCid(goodVoteRequests.map(_.contractId))
            votes = result.flatMap(_.payload.votes.values().asScala)
          } yield {
            votes should contain theSameElementsAs (goodVotes.flatten)
          }
        }
      }
    }

    "lookupLatestTransferCommandEvent" should {
      def createTransferCommand(
          store: ScanStore,
          externalPartyRules: Contract[
            splice.externalpartyamuletrules.ExternalPartyAmuletRules.ContractId,
            splice.externalpartyamuletrules.ExternalPartyAmuletRules,
          ],
          transferCmd: Contract[
            splice.externalpartyamuletrules.TransferCommand.ContractId,
            splice.externalpartyamuletrules.TransferCommand,
          ],
      ) = {
        dummyDomain.exercise(
          externalPartyRules,
          interfaceId = Some(
            splice.externalpartyamuletrules.ExternalPartyAmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID
          ),
          ExternalPartyAmuletRules_CreateTransferCommand.choice.name,
          new splice.externalpartyamuletrules.ExternalPartyAmuletRules_CreateTransferCommand(
            transferCmd.payload.sender,
            transferCmd.payload.receiver,
            transferCmd.payload.delegate,
            transferCmd.payload.amount,
            transferCmd.payload.expiresAt,
            transferCmd.payload.nonce,
          ).toValue,
          new splice.externalpartyamuletrules.ExternalPartyAmuletRules_CreateTransferCommandResult(
            transferCmd.contractId
          ).toValue,
          nextOffset(),
        )(
          store.multiDomainAcsStore
        )
      }

      "transitions from Created to Sent" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()

          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Send.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Send(
              mkPaymentTransferContext(rules.contractId),
              Seq.empty.asJava,
              None.toJava,
              counter.contractId,
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_SendResult(
              new splice.externalpartyamuletrules.transfercommandresult.TransferCommandResultSuccess(
                mkTransferResult(
                  round = 0,
                  inputAppRewardAmount = 0,
                  inputAmuletAmount = 42.0,
                  inputValidatorRewardAmount = 0,
                  inputSvRewardAmount = 0,
                  balanceChanges = Map(
                    user1.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
                      BigDecimal(42.0).bigDecimal,
                      holdingFee.bigDecimal,
                    )
                  ),
                  amuletPrice = 0.0005,
                )
              ),
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Sent(TransferCommandSent()),
              )
          )
        } yield succeed
      }

      "transitions from Created to Failed" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Send.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Send(
              mkPaymentTransferContext(rules.contractId),
              Seq.empty.asJava,
              None.toJava,
              counter.contractId,
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_SendResult(
              new splice.externalpartyamuletrules.transfercommandresult.TransferCommandResultFailure(
                new splice.amuletrules.invalidtransferreason.ITR_Other("cool reason")
              ),
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Failed(
                  TransferCommandFailed("ITR_Other(cool reason)")
                ),
              )
          )
        } yield succeed
      }
      "transitions from Created to Withdrawn" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Withdraw.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Withdraw(
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_WithdrawResult(
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Withdrawn(TransferCommandWithdrawn()),
              )
          )
        } yield succeed
      }

      "transitions from Created to Expired" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Expire.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Expire(
              dsoParty.toProtoPrimitive
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_ExpireResult(
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Expired(TransferCommandExpired()),
              )
          )
        } yield succeed
      }

      "filters by sender and nonce" in {
        for {
          store <- mkStore()
          transferCmd1 = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          // different nonce, same sender
          transferCmd2 = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            1L,
          )
          // same nonce, different sender
          transferCmd3 = transferCommand(
            userParty(2),
            userParty(1),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          // same nonce, same sender, conflicts with transferCmd1
          transferCmd4 = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 1L, 10)
          _ = result shouldBe Map.empty
          result <- store.lookupLatestTransferCommandEvents(userParty(2), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx1 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd1,
          )
          transferCmd1Status =
            TransferCommandTxLogEntry(
              EventId.prefixedFromUpdateIdAndNodeId(tx1.getUpdateId, 0),
              PartyId.tryFromProtoPrimitive(transferCmd1.payload.sender),
              transferCmd1.payload.nonce,
              transferCmd1.contractId.contractId,
              TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
            )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(transferCmd1.contractId -> transferCmd1Status)
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 1L, 10)
          _ = result shouldBe Map.empty
          result <- store.lookupLatestTransferCommandEvents(userParty(2), 0L, 10)
          _ = result shouldBe Map.empty
          tx2 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd2,
          )
          tx3 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd3,
          )
          tx4 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd4,
          )
          transferCmd2Status = TransferCommandTxLogEntry(
            EventId.prefixedFromUpdateIdAndNodeId(tx2.getUpdateId, 0),
            PartyId.tryFromProtoPrimitive(transferCmd2.payload.sender),
            transferCmd2.payload.nonce,
            transferCmd2.contractId.contractId,
            TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
          transferCmd3Status = TransferCommandTxLogEntry(
            EventId.prefixedFromUpdateIdAndNodeId(tx3.getUpdateId, 0),
            PartyId.tryFromProtoPrimitive(transferCmd3.payload.sender),
            transferCmd3.payload.nonce,
            transferCmd3.contractId.contractId,
            TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
          transferCmd4Status = TransferCommandTxLogEntry(
            EventId.prefixedFromUpdateIdAndNodeId(tx4.getUpdateId, 0),
            PartyId.tryFromProtoPrimitive(transferCmd4.payload.sender),
            transferCmd4.payload.nonce,
            transferCmd4.contractId.contractId,
            TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd1.contractId -> transferCmd1Status,
            transferCmd4.contractId -> transferCmd4Status,
          )
          resultLimit <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 1)
          _ = resultLimit shouldBe Map(
            transferCmd1.contractId -> transferCmd1Status
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 1L, 10)
          _ = result shouldBe Map(transferCmd2.contractId -> transferCmd2Status)
          result <- store.lookupLatestTransferCommandEvents(userParty(2), 0L, 10)
          _ = result shouldBe Map(transferCmd3.contractId -> transferCmd3Status)
        } yield succeed
      }
    }
  }

  protected def mkStore(
      dsoParty: PartyId = dsoParty
  ): Future[ScanStore]

  private lazy val user1 = userParty(1)
  private lazy val user2 = userParty(2)

  implicit class ScanStoreExt(store: ScanStore) {
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def listByType[T](beginAfterEventId: Option[String], sortOrder: SortOrder, limit: Int)(implicit
        tag: ClassTag[T]
    ): Future[Seq[T]] = {
      store
        .listTransactions(beginAfterEventId, sortOrder, PageLimit.tryCreate(limit))
        .map(_.collect {
          case c if tag.runtimeClass.isInstance(c) => c.asInstanceOf[T]
        }.toSeq)
    }
  }
}
trait AmuletTransferUtil { self: StoreTest =>
  def mkInputAmulet() = {
    new splice.amuletrules.transferinput.InputAmulet(
      new splice.amulet.Amulet.ContractId(nextCid())
    )
  }

  def mkTransferOutput(
      receiver: PartyId,
      amount: BigDecimal,
      receiverFeeRatio: BigDecimal = BigDecimal(0.0),
  ): splice.amuletrules.TransferOutput =
    new splice.amuletrules.TransferOutput(
      receiver.toProtoPrimitive,
      receiverFeeRatio.bigDecimal,
      amount.bigDecimal,
      Optional.empty(),
    )

  def mkTransfer(receiver: PartyId, amount: Double) =
    new splice.amuletrules.Transfer(
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
      java.util.List.of(mkInputAmulet()),
      java.util.List.of(mkTransferOutput(receiver, amount)),
    )

  def mkTransferContext() = new splice.amuletrules.TransferContext(
    new roundCodegen.OpenMiningRound.ContractId(nextCid()),
    java.util.Map.of(),
    java.util.Map.of(),
    Optional.empty(),
  )

  def mkPaymentTransferContext(amuletRules: splice.amuletrules.AmuletRules.ContractId) =
    new splice.amuletrules.PaymentTransferContext(
      amuletRules,
      mkTransferContext(),
    )

  def mkTransferInputOutput(
      sender: PartyId,
      provider: PartyId,
      transferInputs: List[splice.amuletrules.TransferInput],
      transferOutputs: List[splice.amuletrules.TransferOutput],
  ): splice.amuletrules.Transfer =
    new splice.amuletrules.Transfer(
      sender.toProtoPrimitive,
      provider.toProtoPrimitive,
      transferInputs.asJava,
      transferOutputs.asJava,
    )

  def mkAmuletRules_Transfer(transfer: splice.amuletrules.Transfer) =
    new splice.amuletrules.AmuletRules_Transfer(
      transfer,
      mkTransferContext(),
    ).toValue

  def mkAmuletRulesTransfer(receiver: PartyId, amount: Double) =
    new splice.amuletrules.AmuletRules_Transfer(
      mkTransfer(receiver, amount),
      mkTransferContext(),
    ).toValue

  def mkTransferSummary(
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputSvRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
  ) = new splice.amuletrules.TransferSummary(
    damlDecimal(inputAppRewardAmount),
    damlDecimal(inputValidatorRewardAmount),
    damlDecimal(inputSvRewardAmount),
    damlDecimal(inputAmuletAmount),
    balanceChanges.asJava,
    damlDecimal(0.0),
    java.util.List.of(damlDecimal(0.0)),
    damlDecimal(0.0),
    damlDecimal(0.0),
    damlDecimal(amuletPrice),
    // the validator faucet amount is already included in the `inputValidatorRewardAmount`,
    // We'll set this here once we add support for showing faucet coupon rewards separately
    // from the usage-based validator rewards.
    // TODO(#9824): track faucet coupon inputs separately
    java.util.Optional.empty(),
  )

  def mkTransferResult(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputSvRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
  ) =
    new splice.amuletrules.TransferResult(
      new splice.types.Round(round),
      mkTransferSummary(
        inputAppRewardAmount,
        inputValidatorRewardAmount,
        inputSvRewardAmount,
        inputAmuletAmount,
        balanceChanges,
        amuletPrice,
      ),
      java.util.List.of(),
      Optional.empty(),
    )

  def mkTransferResultRecord(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputSvRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
  ) = mkTransferResult(
    round,
    inputAppRewardAmount,
    inputValidatorRewardAmount,
    inputSvRewardAmount,
    inputAmuletAmount,
    balanceChanges,
    amuletPrice,
  ).toValue

  def mkAmuletRules_BuyMemberTrafficResult(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
      memberTrafficCid: MemberTraffic.ContractId,
  ) =
    new AmuletRules_BuyMemberTrafficResult(
      new Round(round),
      mkTransferSummary(
        inputAppRewardAmount,
        inputValidatorRewardAmount,
        // TODO (#9173): also test for sv rewards once the scan store supports them
        0.0,
        inputAmuletAmount,
        balanceChanges,
        amuletPrice,
      ),
      new java.math.BigDecimal(inputAmuletAmount),
      memberTrafficCid,
      Optional.empty(),
    ).toValue

  def amuletRulesBuyMemberTrafficTransaction(
      provider: PartyId,
      memberId: Member,
      round: Long,
      extraTraffic: Long,
      ccSpent: Double,
  )(offset: Long) = {
    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val amuletRulesCid = nextCid()

    val memberTrafficCid = new MemberTraffic.ContractId(validContractId(round.toInt))

    val createdAmulet = amulet(provider, ccSpent, round, holdingFee)
    val amuletCreateEvent = toCreatedEvent(createdAmulet, signatories = Seq(provider, dsoParty))
    val amuletArchiveEvent = exercisedEvent(
      createdAmulet.contractId.contractId,
      amuletCodegen.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID,
      Some(splice.amulet.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID),
      amuletCodegen.Amulet.CHOICE_Archive.name,
      consuming = true,
      new DamlRecord(),
      damlUnit.getInstance(),
    )

    mkExerciseTx(
      offset,
      exercisedEvent(
        amuletRulesCid,
        splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        splice.amuletrules.AmuletRules.CHOICE_AmuletRules_BuyMemberTraffic.name,
        consuming = false,
        new splice.amuletrules.AmuletRules_BuyMemberTraffic(
          java.util.List.of(),
          mkTransferContext(),
          provider.toProtoPrimitive,
          memberId.toProtoPrimitive,
          dummyDomain.toProtoPrimitive,
          domainMigrationId,
          extraTraffic,
        ).toValue,
        mkAmuletRules_BuyMemberTrafficResult(
          round = round,
          inputAppRewardAmount = 0,
          inputValidatorRewardAmount = 0,
          inputAmuletAmount = ccSpent,
          balanceChanges = Map.empty,
          amuletPrice = 0.0005,
          memberTrafficCid = memberTrafficCid,
        ),
      ),
      Seq(
        // we don't care what the first event is for the store's purposes
        // also, the creation of the burnt amulet should occur somewhere in the tx tree
        amuletCreateEvent,
        amuletArchiveEvent, // the third event has to be a amulet burn
      ),
      dummyDomain,
    )
  }

  /** A AmuletRules_Mint exercise event with one child Amulet create event */
  def mintTransaction(
      receiver: PartyId,
      amount: BigDecimal,
      round: Long,
      ratePerRound: BigDecimal,
      amuletPrice: Double = 1.0,
  )(
      offset: Long
  ) = {
    val amuletContract = amulet(receiver, amount, round, ratePerRound)

    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val amuletRulesCid = nextCid()
    val openMiningRoundCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        amuletRulesCid,
        splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        splice.amuletrules.AmuletRules.CHOICE_AmuletRules_Mint.name,
        consuming = false,
        new splice.amuletrules.AmuletRules_Mint(
          receiver.toProtoPrimitive,
          amuletContract.payload.amount.initialAmount,
          new roundCodegen.OpenMiningRound.ContractId(openMiningRoundCid),
        ).toValue,
        new AmuletRules_MintResult(
          new splice.amulet.AmuletCreateSummary[amuletCodegen.Amulet.ContractId](
            amuletContract.contractId,
            new java.math.BigDecimal(amuletPrice),
            new Round(round),
          )
        ).toValue,
      ),
      Seq(toCreatedEvent(amuletContract, signatories = Seq(receiver, dsoParty))),
      dummyDomain,
    )
  }

  def mkAmuletExpire() =
    new amuletCodegen.Amulet_Expire(
      new roundCodegen.OpenMiningRound.ContractId(nextCid())
    ).toValue

  def mkLockedAmuletExpireAmulet() =
    new amuletCodegen.LockedAmulet_ExpireAmulet(
      new roundCodegen.OpenMiningRound.ContractId(nextCid())
    ).toValue

  def mkAmuletExpireResult(
      owner: PartyId,
      round: Long,
      changeToInitialAmountAsOfRoundZero: BigDecimal,
      changeToHoldingFeesRate: BigDecimal,
  ) =
    new Amulet_ExpireResult(
      new splice.amulet.AmuletExpireSummary(
        owner.toProtoPrimitive,
        new splice.types.Round(round),
        changeToInitialAmountAsOfRoundZero.bigDecimal,
        changeToHoldingFeesRate.bigDecimal,
      )
    ).toValue

  def amuletTemplate(amount: Double, owner: PartyId) = {
    new Amulet(
      dsoParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      expiringAmount(amount),
    )
  }

  def expiringAmount(amount: Double) = new splice.fees.ExpiringAmount(
    numeric(amount),
    new splice.types.Round(0L),
    new splice.fees.RatePerRound(numeric(amount)),
  )

  def dsoRules(
      party: PartyId,
      svs: java.util.Map[String, dsorulesCodegen.SvInfo] = Collections.emptyMap(),
      epoch: Long = 123,
  ) = {
    val templateId = dsorulesCodegen.DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID
    val newSynchronizerId = "new-domain-id"
    val template = new dsorulesCodegen.DsoRules(
      dsoParty.toProtoPrimitive,
      epoch,
      svs,
      Collections.emptyMap(),
      party.toProtoPrimitive,
      new dsorulesCodegen.DsoRulesConfig(
        1,
        1,
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new decentralizedsynchronizerCodegen.SynchronizerNodeConfigLimits(
          new cometbftCodegen.CometBftConfigLimits(1, 1, 1, 1, 1)
        ),
        1,
        new decentralizedsynchronizerCodegen.DsoDecentralizedSynchronizerConfig(
          Collections.emptyMap(),
          newSynchronizerId,
          newSynchronizerId,
        ),
        Optional.empty(),
      ),
      Collections.emptyMap(),
      true,
    )
    contract(
      identifier = templateId,
      contractId = new dsorulesCodegen.DsoRules.ContractId(nextCid()),
      payload = template,
    )
  }

  def ansEntry(
      n: Int,
      name: String,
      expiresAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
  ) = {
    val template = new AnsEntry(
      userParty(n).toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      expiresAt,
    )

    contract(
      AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID,
      new AnsEntry.ContractId(nextCid()),
      template,
    )
  }

  def memberTraffic(member: Member, domainMigrationId: Long, totalPurchased: Long) = {
    val template = new MemberTraffic(
      dsoParty.toProtoPrimitive,
      member.toProtoPrimitive,
      dummyDomain.toProtoPrimitive,
      domainMigrationId,
      totalPurchased,
      1,
      numeric(1.0),
      numeric(1.0),
    )

    contract(
      MemberTraffic.TEMPLATE_ID_WITH_PACKAGE_ID,
      new MemberTraffic.ContractId(nextCid()),
      template,
    )
  }

  lazy val domain = dummyDomain.toProtoPrimitive
}

class DbScanStoreTest
    extends ScanStoreTest
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(
      dsoParty: PartyId
  ): Future[ScanStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.amuletNameService.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbScanStore(
      key = ScanStore.Key(dsoParty),
      storage,
      // to allow aggregating from round zero without previous round aggregate
      isFirstSv = true,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      // required to instantiate a DbScanStore, returns none not to affect this test.
      _ =>
        new ScanAggregatesReader() {
          def readRoundAggregateFromDso(round: Long)(implicit
              ec: ExecutionContext,
              traceContext: TraceContext,
          ): Future[Option[ScanAggregator.RoundAggregate]] = Future.successful(None)
          def close(): Unit = ()
        },
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      participantId = mkParticipantId("ScanStoreTest"),
      new DbScanStoreMetrics(new NoOpMetricsFactory()),
    )(parallelExecutionContext, implicitly, implicitly)

    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(nextOffset(), Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(SynchronizerAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()

  "getTopValidatorLicenses" should {

    "return the top `limit` validator licenses by number of rounds collected" in {
      // total 1001
      val first = validatorLicense(
        userParty(9001),
        dsoParty,
        Some(new FaucetState(new Round(0), new Round(1000), 0L)),
      )
      // total 1000
      val almostFirst = validatorLicense(
        userParty(2),
        dsoParty,
        Some(new FaucetState(new Round(0), new Round(1000), 1L)),
      )
      // total 681
      val third = validatorLicense(
        userParty(2),
        dsoParty,
        Some(new FaucetState(new Round(700), new Round(1000), 20L)),
      )
      // total 2
      val outOfLimit = validatorLicense(
        userParty(6),
        dsoParty,
        Some(new FaucetState(new Round(999), new Round(1000), 0L)),
      )
      for {
        store <- mkStore()
        _ <- dummyDomain.create(outOfLimit)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(almostFirst)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(first)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(third)(store.multiDomainAcsStore)
        result <- store.getTopValidatorLicenses(PageLimit.tryCreate(3))
      } yield result shouldBe Seq(first, almostFirst, third)
    }
  }

  "getValidatorFaucetsByValidator" should {

    "return the validator license of a specified validator" in {
      val alice = userParty(443)
      val aliceValidatorLicense = validatorLicense(
        alice,
        dsoParty,
        Some(new FaucetState(new Round(0), new Round(1000), 0L)),
      )
      val bob = userParty(444)
      val bobValidatorLicense = validatorLicense(
        bob,
        dsoParty,
        Some(new FaucetState(new Round(1), new Round(1001), 1L)),
      )
      val charles = userParty(445)
      val charlesValidatorLicense = validatorLicense(
        charles,
        dsoParty,
        Some(new FaucetState(new Round(3), new Round(1002), 2L)),
      )
      for {
        store <- mkStore()
        _ <- dummyDomain.create(bobValidatorLicense)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(aliceValidatorLicense)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(charlesValidatorLicense)(store.multiDomainAcsStore)
        result <- store.getValidatorLicenseByValidator(
          Vector(alice, bob)
        )
      } yield {
        result should contain(aliceValidatorLicense)
        result should contain(bobValidatorLicense)
        result should not contain charlesValidatorLicense
      }
    }
  }
}
