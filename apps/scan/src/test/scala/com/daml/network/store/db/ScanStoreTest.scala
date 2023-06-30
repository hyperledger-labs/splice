package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.{Coin, CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.config.DomainConfig
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanDomainConfig}
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.store.db.DbScanStore
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.StoreTest
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.{CNNodeUtil, Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

import java.time.{Duration, Instant}
import scala.annotation.unused
import scala.concurrent.Future

abstract class ScanStoreTest extends StoreTest with HasExecutionContext {

  "ScanStore" should {

    "lookupCoinRules" should {

      "find the latest coin rules" in {
        val cr = coinRules(1)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .lookupCoinRules()
              .futureValue
              .map(_.contract) should be(Some(cr))
          }
        }
      }

    }

    "lookupCoinRulesV1Test" should {

      "find the coin rules" in {
        val cr = coinRulesV1Test(1)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .lookupCoinRulesV1Test()
              .futureValue
              .map(_.contract) should be(Some(cr))
          }
        }
      }

    }

    "lookupValidatorTraffic" should {

      "return the validator traffic of the wanted validator" in {
        val wanted = validatorTraffic(userParty(1))
        val unwanted = validatorTraffic(userParty(2))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .lookupValidatorTraffic(userParty(1))
              .futureValue should be(Some(wanted))
          }
        }
      }

    }

    "listImportCrates" should {

      "return all import crates of a receiver" in {
        val wanted1 = importCrate(userParty(1), 1)
        val unwanted = importCrate(userParty(2), 1)
        val wanted2 = importCrate(userParty(1), 2)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wanted2)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .listImportCrates(userParty(1).toProtoPrimitive)
              .futureValue
              .map(_.contract) should contain theSameElementsAs Set(wanted1, wanted2)
          }
        }
      }

    }

    "findFeaturedAppRight" should {

      "return the FeaturedAppRight of the wanted provider" in {
        val wanted = featuredAppRight(userParty(1))
        val unwanted = featuredAppRight(userParty(2))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .findFeaturedAppRight(dummyDomain, userParty(1))
              .futureValue should be(Some(wanted))
          }
        }
      }

    }

  }

  protected def mkStore(): Future[ScanStore]

  private def coinRules(n: Int) = {
    val template = new CoinRules(
      svcParty.toProtoPrimitive,
      CNNodeUtil.defaultCoinConfigSchedule(
        NonNegativeFiniteDuration(Duration.ofMinutes(10)),
        10,
        dummyDomain,
      ),
      CNNodeUtil.defaultEnabledChoices,
      true,
      false,
    )
    Contract(
      CoinRules.TEMPLATE_ID,
      new CoinRules.ContractId(n.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def coinRulesV1Test(n: Int) = {
    val template = new CoinRulesV1Test(
      svcParty.toProtoPrimitive,
      CNNodeUtil.defaultCoinConfigSchedule(
        NonNegativeFiniteDuration(Duration.ofMinutes(10)),
        10,
        dummyDomain,
      ),
      CNNodeUtil.defaultEnabledChoices,
      true,
      false,
    )
    Contract(
      CoinRulesV1Test.TEMPLATE_ID,
      new CoinRulesV1Test.ContractId(n.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def validatorTraffic(validatorParty: PartyId) = {
    val template = new ValidatorTraffic(
      svcParty.toProtoPrimitive,
      validatorParty.toProtoPrimitive,
      3,
      1,
      numeric(1),
      numeric(1),
      Instant.now(),
      domain,
    )
    Contract(
      ValidatorTraffic.TEMPLATE_ID,
      new ValidatorTraffic.ContractId(n.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def importCrate(receiver: PartyId, n: Int) = {
    val template = new ImportCrate(
      svcParty.toProtoPrimitive,
      receiver.toProtoPrimitive,
      true,
      coinTemplate(n.toDouble, receiver),
    )
    Contract(
      ImportCrate.TEMPLATE_ID,
      new ImportCrate.ContractId(s"${receiver.toProtoPrimitive}::$n"),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def featuredAppRight(providerParty: PartyId) = {
    val template = new FeaturedAppRight(svcParty.toProtoPrimitive, providerParty.toProtoPrimitive)
    Contract(
      FeaturedAppRight.TEMPLATE_ID,
      new FeaturedAppRight.ContractId(providerParty.toProtoPrimitive),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  @unused("use for coinbalance, when it's available")
  private def coin(amount: Double, owner: PartyId) = {
    val template = coinTemplate(amount, owner)
    Contract(
      Coin.TEMPLATE_ID,
      new Coin.ContractId(s"${owner.toProtoPrimitive}#$amount"),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def coinTemplate(amount: Double, owner: PartyId) = {
    new Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      expiringAmount(amount),
    )
  }

  private def expiringAmount(amount: Double) = new cc.fees.ExpiringAmount(
    numeric(amount),
    new cc.api.v1.round.Round(0L),
    new cc.fees.RatePerRound(numeric(amount)),
  )

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
}

class InMemoryScanStoreTest extends ScanStoreTest {
  override protected def mkStore(): Future[InMemoryScanStore] = {
    // TODO (#6194): figure out what values to use for participantClient & connection. They're currently unused.
    val store = new InMemoryScanStore(
      svcParty,
      ScanAppBackendConfig(
        svUser = svcParty.toProtoPrimitive,
        enableCoinRulesUpgrade = true,
        participantClient = null,
        domains = new ScanDomainConfig(DomainConfig(DomainAlias.tryCreate(domain))),
      ),
      loggerFactory,
      transactionTreeSource = TransactionTreeSource.Unused,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }
}

class DbScanStoreTest
    extends ScanStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbScanStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        Seq(
          "dar/canton-coin-0.1.1.dar"
        )
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    // TODO (#6194): figure out what values to use for participantClient & connection. They're currently unused.
    val store = new DbScanStore(
      svcParty,
      storage,
      ScanAppBackendConfig(
        svUser = svcParty.toProtoPrimitive,
        enableCoinRulesUpgrade = true,
        participantClient = null,
        domains = new ScanDomainConfig(DomainConfig(DomainAlias.tryCreate(domain))),
      ),
      loggerFactory,
      transactionTreeSource = TransactionTreeSource.Unused,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}
