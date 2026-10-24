package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.{DamlRecord, Unit as damlUnit}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, Amulet_ExpireResult}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AmuletRules_BuyMemberTrafficResult,
  AmuletRules_MintResult,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsEntry
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer as decentralizedsynchronizerCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_UpdateSvRewardWeight,
  Reason,
  Vote,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_UpdateSvRewardWeight
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.FaucetState
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  cometbft as cometbftCodegen,
  dsorules as dsorulesCodegen,
  round as roundCodegen,
}
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.history.*
import org.lfdecentralizedtrust.splice.scan.store.db.{DbScanStore, DbScanStoreMetrics}
import org.lfdecentralizedtrust.splice.scan.store.*
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState.Assigned
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.SpliceUtil.damlDecimal
import org.lfdecentralizedtrust.splice.util.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.scan.config.AnalyzableTimeWindowConfig
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart.{
  InitializeAcsAtLatestOffset,
  InitializeAcsAtOffset,
  ResumeAtOffset,
  UpdateHistoryInitAtLatestPrunedOffset,
}
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId

abstract class ScanStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with StoreErrors
    with AmuletTransferUtil {

  "ScanStore" should {
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

    "getTotalPurchasedMemberTraffic" should {

      "return the sum over all traffic contracts for the member" in {
        val namespace = Namespace(Fingerprint.tryFromString(s"dummy"))
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
            StoreMemberId(goodMember),
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
            .lookupFeaturedAppRight(userParty(1))
            .futureValue should be(expectedResult)
        }
      }
    }

    "listFeaturedAppRightsByProvider" should {

      "return the FeaturedAppRights of the wanted provider in creation order, up to the limit" in {
        val wanted = (1 to 3).map(_ => featuredAppRight(userParty(1)))
        val unwanted = featuredAppRight(userParty(2))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(wanted :+ unwanted)(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          all <- store.listFeaturedAppRightsByProvider(userParty(1), HardLimit.tryCreate(3))
          page <- store.listFeaturedAppRightsByProvider(userParty(1), PageLimit.tryCreate(2))
          hard <- loggerFactory.assertLogs(
            store.listFeaturedAppRightsByProvider(userParty(1), HardLimit.tryCreate(2)),
            _.warningMessage should include(
              "Size of the result exceeded the limit in listFeaturedAppRightsByProvider"
            ).and(include("Result size: 3. Limit: 2")),
          )
        } yield {
          all.map(_.contract) shouldBe wanted
          page.map(_.contract) shouldBe wanted.take(2)
          hard.map(_.contract) shouldBe wanted.take(2)
        }
      }
    }

    "ingest GovernanceLocks of the DSO and skip those of other parties" in {
      val wantedFull = governanceLock(userParty(1), amount = BigDecimal(10))
      val unwantedFull = governanceLock(userParty(2), amount = BigDecimal(20), dso = userParty(3))
      val vestingEndTime = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)
      val wantedVesting =
        governanceLock(userParty(1), amount = BigDecimal(10), vestingEndTime = Some(vestingEndTime))
      val unwantedVesting =
        governanceLock(
          userParty(2),
          amount = BigDecimal(20),
          vestingEndTime = Some(vestingEndTime),
          dso = userParty(3),
        )
      for {
        store <- mkStore()
        _ <- MonadUtil.sequentialTraverse(
          Seq(wantedFull, unwantedFull, wantedVesting, unwantedVesting)
        )(dummyDomain.create(_)(store.multiDomainAcsStore))
        result <- store.multiDomainAcsStore.listContracts(
          splice.governancelock.GovernanceLock.COMPANION
        )
      } yield {
        result.map(_.contractId) should contain theSameElementsAs
          Seq(wantedFull, wantedVesting).map(_.contractId)
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

    "votes" should {

      "listVoteRequestResults" should {

        "list all past VoteRequestResult" in {
          val store = mkStore().futureValue
          val voteRequestContracts = mkVoteRequests()
          assertListOfAllPastVoteRequestResults(voteRequestContracts, store)
        }

        "return results in effective-at order, independent of ingestion and record time" in {
          val baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS)
          def recordTime(n: Int) = baseTime.plusSeconds(n.toLong)
          def effectiveAt(n: Int) = baseTime.plus((5 - n).toLong, ChronoUnit.DAYS)
          val voteRequests = (1 to 4).map { n =>
            voteRequest(
              requester = userParty(n),
              votes = Seq(
                new Vote(userParty(n).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
              ),
            )
          }
          val results =
            (1 to 4).map(n =>
              mkVoteRequestResult(voteRequests(n - 1), effectiveAt = effectiveAt(n))
            )
          def closeVoteRequest(store: ScanStore, n: Int) =
            dummyDomain.exercise(
              contract = dsoRules(dsoParty),
              interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = DsoRulesCloseVoteRequest.choice.name,
              choiceArgument = mkCloseVoteRequest(voteRequests(n - 1).contractId),
              exerciseResult = results(n - 1).toValue,
              recordTime = recordTime(n),
            )(store.multiDomainAcsStore)
          for {
            store <- mkStore()
            _ <- MonadUtil.sequentialTraverse(voteRequests)(
              dummyDomain.create(_)(store.multiDomainAcsStore)
            )
            _ <- closeVoteRequest(store, 3)
            _ <- closeVoteRequest(store, 4)
            _ <- closeVoteRequest(store, 2)
            _ <- closeVoteRequest(store, 1)
            page1 <- store.listVoteRequestResults(
              VoteResultsFilters(),
              PageLimit.tryCreate(3),
            )
            page2 <- store.listVoteRequestResults(
              VoteResultsFilters(),
              PageLimit.tryCreate(3),
              page1.nextPageToken,
            )
          } yield {
            page1.resultsInPage.map(_.request.requester) shouldBe
              Seq(1, 2, 3).map(userParty(_).toProtoPrimitive)
            page2.resultsInPage.map(_.request.requester) shouldBe
              Seq(4).map(userParty(_).toProtoPrimitive)
          }
        }

        "order by coalesce(effectiveAt, completedAt) across mixed precision, tie-broken by entry_number" in {
          val base = Instant.parse("2024-03-01T10:00:00Z")
          val accepted = Set(1, 3, 4, 6)
          val sortKeyAt = Map(
            1 -> base.plusSeconds(1),
            2 -> base.plusSeconds(2).plusMillis(500),
            3 -> base.plusSeconds(10),
            4 -> base.plusSeconds(10),
            5 -> base.plusSeconds(20).plusNanos(123000),
            6 -> base.plusSeconds(45).plusMillis(900),
          )
          def recordTime(n: Int) = base.plusSeconds(100L + n.toLong)
          val voteRequests = (1 to 6).map { n =>
            voteRequest(
              requester = userParty(n),
              votes = Seq(
                new Vote(userParty(n).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
              ),
            )
          }
          val results =
            (1 to 6).map(n =>
              if (accepted(n)) mkVoteRequestResult(voteRequests(n - 1), effectiveAt = sortKeyAt(n))
              else mkRejectedVoteRequestResult(voteRequests(n - 1), completedAt = sortKeyAt(n))
            )
          def closeVoteRequest(store: ScanStore, n: Int) =
            dummyDomain.exercise(
              contract = dsoRules(dsoParty),
              interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = DsoRulesCloseVoteRequest.choice.name,
              choiceArgument = mkCloseVoteRequest(voteRequests(n - 1).contractId),
              exerciseResult = results(n - 1).toValue,
              recordTime = recordTime(n),
            )(store.multiDomainAcsStore)
          for {
            store <- mkStore()
            _ <- MonadUtil.sequentialTraverse(voteRequests)(
              dummyDomain.create(_)(store.multiDomainAcsStore)
            )
            _ <- closeVoteRequest(store, 3)
            _ <- closeVoteRequest(store, 1)
            _ <- closeVoteRequest(store, 5)
            _ <- closeVoteRequest(store, 4)
            _ <- closeVoteRequest(store, 2)
            _ <- closeVoteRequest(store, 6)
            page1 <- store.listVoteRequestResults(
              VoteResultsFilters(),
              PageLimit.tryCreate(3),
            )
            page2 <- store.listVoteRequestResults(
              VoteResultsFilters(),
              PageLimit.tryCreate(3),
              page1.nextPageToken,
            )
          } yield {
            page1.resultsInPage.map(_.request.requester) shouldBe
              Seq(6, 5, 4).map(userParty(_).toProtoPrimitive)
            page2.resultsInPage.map(_.request.requester) shouldBe
              Seq(3, 2, 1).map(userParty(_).toProtoPrimitive)
          }
        }
      }

      "countVoteRequestResults" should {

        "count vote results matching the filters" in {
          val base = Instant.parse("2024-03-01T10:00:00Z")
          val accepted = Set(1, 3, 4, 6)
          def sortKeyAt(n: Int) = base.plusSeconds(n.toLong)
          def recordTime(n: Int) = base.plusSeconds(100L + n.toLong)
          val voteRequests = (1 to 6).map { n =>
            voteRequest(
              requester = userParty(n),
              votes = Seq(
                new Vote(userParty(n).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
              ),
            )
          }
          val results =
            (1 to 6).map(n =>
              if (accepted(n)) mkVoteRequestResult(voteRequests(n - 1), effectiveAt = sortKeyAt(n))
              else mkRejectedVoteRequestResult(voteRequests(n - 1), completedAt = sortKeyAt(n))
            )
          def closeVoteRequest(store: ScanStore, n: Int) =
            dummyDomain.exercise(
              contract = dsoRules(dsoParty),
              interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = DsoRulesCloseVoteRequest.choice.name,
              choiceArgument = mkCloseVoteRequest(voteRequests(n - 1).contractId),
              exerciseResult = results(n - 1).toValue,
              recordTime = recordTime(n),
            )(store.multiDomainAcsStore)
          for {
            store <- mkStore()
            _ <- MonadUtil.sequentialTraverse(voteRequests)(
              dummyDomain.create(_)(store.multiDomainAcsStore)
            )
            _ <- MonadUtil.sequentialTraverse(1 to 6)(closeVoteRequest(store, _))
            total <- store.countVoteRequestResults(VoteResultsFilters())
            acceptedCount <- store.countVoteRequestResults(
              VoteResultsFilters(accepted = Some(true))
            )
            rejectedCount <- store.countVoteRequestResults(
              VoteResultsFilters(accepted = Some(false))
            )
            requesterCount <- store.countVoteRequestResults(
              VoteResultsFilters(requester = Some(userParty(1).toProtoPrimitive))
            )
          } yield {
            total shouldBe 6L
            acceptedCount shouldBe 4L
            rejectedCount shouldBe 2L
            requesterCount shouldBe 1L
          }
        }
      }

      "lookupLatestSvRewardWeightChange" should {

        "return the weight of the latest accepted UpdateSvRewardWeight before the given time" in {
          val sv = userParty(42)
          val firstVoteAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
          val secondVoteAt = firstVoteAt.plusSeconds(10)
          def updateWeightAction(weight: Long) = new ARC_DsoRules(
            new SRARC_UpdateSvRewardWeight(
              new DsoRules_UpdateSvRewardWeight(sv.toProtoPrimitive, weight)
            )
          )
          val firstVote =
            voteRequest(
              requester = userParty(1),
              votes = Seq.empty,
              action = updateWeightAction(30000L),
            )
          val secondVote =
            voteRequest(
              requester = userParty(1),
              votes = Seq.empty,
              action = updateWeightAction(50000L),
            )
          for {
            store <- mkStore()
            noVotes <- store.lookupLatestSvRewardWeightChange(sv, None)
            _ <- dummyDomain.create(firstVote)(store.multiDomainAcsStore)
            _ <- dummyDomain.exercise(
              contract = dsoRules(dsoParty),
              interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = DsoRulesCloseVoteRequest.choice.name,
              mkCloseVoteRequest(firstVote.contractId),
              mkVoteRequestResult(firstVote, effectiveAt = firstVoteAt).toValue,
              txEffectiveAt = firstVoteAt,
              recordTime = firstVoteAt,
            )(store.multiDomainAcsStore)
            _ <- dummyDomain.create(secondVote)(store.multiDomainAcsStore)
            _ <- dummyDomain.exercise(
              contract = dsoRules(dsoParty),
              interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = DsoRulesCloseVoteRequest.choice.name,
              mkCloseVoteRequest(secondVote.contractId),
              mkVoteRequestResult(secondVote, effectiveAt = secondVoteAt).toValue,
              txEffectiveAt = secondVoteAt,
              recordTime = secondVoteAt,
            )(store.multiDomainAcsStore)
            latest <- store.lookupLatestSvRewardWeightChange(sv, None)
            beforeSecondVote <- store.lookupLatestSvRewardWeightChange(
              sv,
              Some(secondVoteAt.toString),
            )
            unknown <- store.lookupLatestSvRewardWeightChange(userParty(999), None)
          } yield {
            noVotes shouldBe None
            latest shouldBe Some(50000L)
            beforeSecondVote shouldBe Some(30000L)
            unknown shouldBe None
          }
        }
      }

      "listVoteRequestsByTrackingCid" should {

        "return all votes by their VoteRequest contract ids" in {
          val goodVotes = (1 to 3).map(n =>
            Seq(n, n + 3)
              .map(i =>
                new Vote(userParty(i).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
              )
          )
          val badVotes = (1 to 3).map(n =>
            Seq(n)
              .map(i =>
                new Vote(userParty(i).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
              )
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
            transferCmd.payload.description,
            Optional.of(dsoParty.toProtoPrimitive),
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

    "lookupContractByRecordTime" should {

      "find the DsoRules contract at a given time" in {
        val now = CantonTimestamp.now()
        val firstDsoRules = dsoRules(dsoParty, epoch = 1)
        val secondDsoRules = dsoRules(dsoParty, epoch = 2)
        val thirdDsoRules = dsoRules(dsoParty, epoch = 3)
        val recordTimeFirst = now.plusSeconds(1).toInstant
        val recordTimeSecond = now.plusSeconds(5).toInstant
        val recordTimeThird = now.plusSeconds(9).toInstant
        for {
          store <- mkStore()
          updateHistory <- mkUpdateHistory(domainMigrationId)
          _ <- updateHistory.ingestionSink.initialize()
          first <- dummyDomain.create(
            firstDsoRules,
            recordTime = recordTimeFirst,
          )(
            updateHistory
          )
          firstRecordTime = CantonTimestamp.fromInstant(first.getRecordTime).getOrElse(now)
          _ <- dummyDomain.create(
            secondDsoRules,
            recordTime = recordTimeSecond,
          )(updateHistory)
          _ <- dummyDomain.create(
            thirdDsoRules,
            recordTime = recordTimeThird,
          )(updateHistory)
          result <- store.lookupContractByRecordTime(
            DsoRules.COMPANION,
            updateHistory,
            firstRecordTime.plusSeconds(1),
          )
        } yield {
          result.value should not be firstDsoRules
          result.value shouldBe secondDsoRules
          result.value should not be thirdDsoRules
        }
      }

      "find the AmuletRules contract at a given time" in {
        val now = CantonTimestamp.now()
        val firstAmuletRules = amuletRules(10)
        val secondAmuletRules = amuletRules(20)
        val thirdAmuletRules = amuletRules(30)
        val recordTimeFirst = now.plusSeconds(1).toInstant
        val recordTimeSecond = now.plusSeconds(5).toInstant
        val recordTimeThird = now.plusSeconds(9).toInstant
        for {
          store <- mkStore()
          updateHistory <- mkUpdateHistory(domainMigrationId)
          _ <- updateHistory.ingestionSink.initialize()
          first <- dummyDomain.create(
            firstAmuletRules,
            recordTime = recordTimeFirst,
          )(
            updateHistory
          )
          firstRecordTime = CantonTimestamp.fromInstant(first.getRecordTime).getOrElse(now)
          _ <- dummyDomain.create(
            secondAmuletRules,
            recordTime = recordTimeSecond,
          )(
            updateHistory
          )
          _ <- dummyDomain.create(
            thirdAmuletRules,
            recordTime = recordTimeThird,
          )(updateHistory)
          result <- store.lookupContractByRecordTime(
            AmuletRules.COMPANION,
            updateHistory,
            firstRecordTime.plusSeconds(1),
          )
        } yield {
          result.value should not be firstAmuletRules
          result.value shouldBe secondAmuletRules
          result.value should not be thirdAmuletRules
        }
      }
    }
  }
  def mkVoteRequests(): Vector[Contract[VoteRequest.ContractId, VoteRequest]] = {
    val voteRequestContract1 = voteRequest(
      requester = userParty(1),
      votes = (1 to 4)
        .map(n =>
          new Vote(
            userParty(n).toProtoPrimitive,
            true,
            new Reason("", ""),
            Optional.empty(),
          )
        ),
    )
    val voteRequestContract2 = voteRequest(
      requester = userParty(2),
      votes = (1 to 4)
        .map(n =>
          new Vote(
            userParty(n).toProtoPrimitive,
            true,
            new Reason("", ""),
            Optional.empty(),
          )
        ),
    )
    Vector(voteRequestContract1, voteRequestContract2)
  }
  def assertListOfAllPastVoteRequestResults(
      voteRequestContracts: Vector[Contract[VoteRequest.ContractId, VoteRequest]],
      store: ScanStore,
  ) = {
    val voteRequestContract1 = voteRequestContracts(0)
    val voteRequestContract2 = voteRequestContracts(1)

    for {
      _ <- dummyDomain.create(voteRequestContract1)(store.multiDomainAcsStore)
      result1 = mkVoteRequestResult(
        voteRequestContract1
      )
      _ <- dummyDomain.exercise(
        contract = dsoRules(dsoParty),
        interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
        choiceName = DsoRulesCloseVoteRequest.choice.name,
        mkCloseVoteRequest(
          voteRequestContract1.contractId
        ),
        result1.toValue,
      )(
        store.multiDomainAcsStore
      )
      _ <- dummyDomain.create(voteRequestContract2)(store.multiDomainAcsStore)
      result2 = mkVoteRequestResult(
        voteRequestContract2,
        effectiveAt = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS),
      )
      _ <- dummyDomain.exercise(
        contract = dsoRules(dsoParty),
        interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
        choiceName = DsoRulesCloseVoteRequest.choice.name,
        mkCloseVoteRequest(
          voteRequestContract2.contractId
        ),
        result2.toValue,
      )(
        store.multiDomainAcsStore
      )
    } yield {
      store
        .listVoteRequestResults(
          VoteResultsFilters(actionName = Some("AddSv"), accepted = Some(true)),
          PageLimit.tryCreate(1),
        )
        .futureValue
        .resultsInPage
        .toList
        .loneElement shouldBe result2
      store
        .listVoteRequestResults(
          VoteResultsFilters(actionName = Some("SRARC_AddSv"), accepted = Some(false)),
          PageLimit.tryCreate(1),
        )
        .futureValue
        .resultsInPage
        .toList
        .size shouldBe (0)
      store
        .listVoteRequestResults(
          VoteResultsFilters(),
          PageLimit.tryCreate(1),
        )
        .futureValue
        .resultsInPage
        .toList
        .size shouldBe (1)
      store
        .listVoteRequestResults(
          VoteResultsFilters(effectiveFrom =
            Some(Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600).toString)
          ),
          PageLimit.tryCreate(1),
        )
        .futureValue
        .resultsInPage
        .toList
        .size shouldBe (0)
      store
        .listVoteRequestResults(
          VoteResultsFilters(effectiveFrom =
            Some(Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(3600).toString)
          ),
          PageLimit.tryCreate(1),
        )
        .futureValue
        .resultsInPage
        .toList
        .size shouldBe (1)
    }
  }

  protected def mkStore(
      dsoParty: PartyId = dsoParty,
      acsStoreDescriptorUserVersion: Option[Long] = None,
      txLogStoreDescriptorUserVersion: Option[Long] = None,
  ): Future[ScanStore]

  protected def mkUpdateHistory(
      migrationId: Long
  ): Future[UpdateHistory]

  private lazy val user1 = userParty(1)
}
trait AmuletTransferUtil { self: StoreTestBase =>
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
      Optional.empty(),
    )

  def mkTransfer(receiver: PartyId, amount: Double) =
    new splice.amuletrules.Transfer(
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
      java.util.List.of(mkInputAmulet()),
      java.util.List.of(mkTransferOutput(receiver, amount)),
      Optional.empty(),
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
      Optional.empty(),
    )

  def mkAmuletRules_Transfer(transfer: splice.amuletrules.Transfer) =
    new splice.amuletrules.AmuletRules_Transfer(
      transfer,
      mkTransferContext(),
      Optional.of(dsoParty.toProtoPrimitive),
    ).toValue

  def mkAmuletRulesTransfer(receiver: PartyId, amount: Double) =
    new splice.amuletrules.AmuletRules_Transfer(
      mkTransfer(receiver, amount),
      mkTransferContext(),
      Optional.of(dsoParty.toProtoPrimitive),
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
    // TODO(#968): track faucet coupon inputs separately
    java.util.Optional.empty(),
    java.util.Optional.empty(),
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
        // TODO (DACH-NY/canton-network-node#9173): also test for sv rewards once the scan store supports them
        0.0,
        inputAmuletAmount,
        balanceChanges,
        amuletPrice,
      ),
      new java.math.BigDecimal(inputAmuletAmount),
      memberTrafficCid,
      Optional.empty(),
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
          Optional.of(dsoParty.toProtoPrimitive),
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
          Optional.empty(),
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
      ),
      Optional.empty(),
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
        Optional.empty(), // voteCooldownTime
        Optional.empty(), // nextScheduledLogicalSynchronizerUpgrade
        Optional.empty(), // svOperationsSwitchOverTimes
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
      dsoParty: PartyId,
      acsStoreDescriptorUserVersion: Option[Long] = None,
      txLogStoreDescriptorUserVersion: Option[Long] = None,
  ): Future[ScanStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.amuletNameService.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    for {
      store <- DbScanStore.create(
        key = ScanStore.Key(dsoParty),
        storage,
        loggerFactory,
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
        domainMigrationId,
        participantId = mkParticipantId("ScanStoreTest"),
        IngestionConfig(),
        new DbScanStoreMetrics(new NoOpMetricsFactory(), loggerFactory, timeouts),
        defaultLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
        acsStoreDescriptorUserVersion,
        txLogStoreDescriptorUserVersion,
      )(parallelExecutionContext, implicitly, implicitly, implicitly)
      initializeResult <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- initializeResult match {
        case ResumeAtOffset(_) | UpdateHistoryInitAtLatestPrunedOffset => Future.unit
        case InitializeAcsAtLatestOffset =>
          store.multiDomainAcsStore.testIngestionSink.ingestAcs(
            nextOffset(),
            Seq.empty,
            Seq.empty,
            Seq.empty,
          )
        case InitializeAcsAtOffset(_) =>
          store.multiDomainAcsStore.testIngestionSink.ingestAcs(
            nextOffset(),
            Seq.empty,
            Seq.empty,
            Seq.empty,
          )
      }
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(SynchronizerAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  override def mkUpdateHistory(
      migrationId: Long
  ): Future[UpdateHistory] = {
    for {
      updateHistory <- UpdateHistory.create(
        storage.underlying, // not under test
        migrationId,
        "update_history_scan_store_test",
        StoreMemberId(mkParticipantId("whatever")),
        dsoParty,
        BackfillingRequirement.BackfillingNotRequired,
        internedStringStore(storage),
        analyzableTimeWindowDuration = AnalyzableTimeWindowConfig.UnlimitedAtw,
        loggerFactory,
        enableImportUpdateBackfill = true,
        HistoryMetrics(NoOpMetricsFactory, migrationId),
      )

      _ <- updateHistory.ingestionSink.initialize()
    } yield updateHistory
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
        withDuplicates <- store.getValidatorLicenseByValidator(
          Vector(alice, bob, alice, alice)
        )
      } yield {
        result should contain(aliceValidatorLicense)
        result should contain(bobValidatorLicense)
        result should not contain charlesValidatorLicense
        withDuplicates should contain theSameElementsAs result
      }
    }
  }
  "Changing the acsStoreDescriptorUserVersion" should {
    val alice = userParty(443)
    val aliceValidatorLicense = validatorLicense(
      alice,
      dsoParty,
      Some(new FaucetState(new Round(0), new Round(1000), 0L)),
    )

    "force re-ingestion of acs" in {
      for {
        // ingestion in these store tests is simulated by directly interacting with the ingestion sink (dummyDomain.create)
        // create store, ingest an update with aliceValidatorLicense
        store <- mkStore()
        _ <- dummyDomain.create(aliceValidatorLicense)(store.multiDomainAcsStore)
        result <- store.getValidatorLicenseByValidator(
          Vector(alice)
        )
        // create store again but now with new storeDescriptor userVersion, ingest an update with aliceValidatorLicense again
        storeReingest <- mkStore(dsoParty = dsoParty, acsStoreDescriptorUserVersion = Some(1L))
        // Below 'dummyDomain.create' would fail on the same storeId for aliceValidatorLicense without a new user version.
        // there is a unique constraint on (store_id, migration_id, contract_id) in the acs table (acs_store_template_sid_mid_cid) that would be violated.
        // Successfully creating the license again here proves that the store has switched to a new store descriptor.
        _ <- dummyDomain.create(aliceValidatorLicense)(storeReingest.multiDomainAcsStore)
        resultAfter <- storeReingest.getValidatorLicenseByValidator(
          Vector(alice)
        )
      } yield {
        result should contain(aliceValidatorLicense)
        resultAfter should contain(aliceValidatorLicense)
      }
    }
  }

  "Changing the txLogStoreDescriptorUserVersion" should {
    "force re-ingestion of txLog" in {
      val activeVoteRequest = voteRequest(
        requester = userParty(4),
        votes = (1 to 4)
          .map(n =>
            new Vote(
              userParty(n).toProtoPrimitive,
              true,
              new Reason("", ""),
              Optional.empty(),
            )
          ),
      )
      for {
        store <- mkStore()
        voteRequestContracts = mkVoteRequests() :+ activeVoteRequest
        _ <- assertListOfAllPastVoteRequestResults(voteRequestContracts, store)
        _ <- dummyDomain.create(activeVoteRequest)(store.multiDomainAcsStore)
        // create the store with a new txLogStoreDescriptorUserVersion and
        // check that it has acs entries but no txLog entries of the previous store
        // this proves that the store descriptor has changed and a new storeId is used.
        storeReingest <- mkStore(
          dsoParty = dsoParty,
          txLogStoreDescriptorUserVersion = Some(1L),
        )
      } yield {
        // new store should not have txLog entry from previous store
        // because ingestion in these store tests is simulated by directly interacting with the ingestion sink
        storeReingest
          .listVoteRequestResults(
            VoteResultsFilters(actionName = Some("AddSv"), accepted = Some(true)),
            PageLimit.tryCreate(1),
          )
          .futureValue
          .resultsInPage
          .toList should have size 0
        // should have the active acs entry
        storeReingest.listVoteRequests().futureValue.toList should contain(activeVoteRequest)
      }
    }
  }
}
