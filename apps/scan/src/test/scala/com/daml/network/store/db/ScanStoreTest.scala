package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.Coin
import com.daml.network.config.DomainConfig
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanDomainConfig}
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.store.db.DbScanStore
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.StoreTest
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

import scala.concurrent.Future

abstract class ScanStoreTest extends StoreTest with HasExecutionContext {

  "ScanStore" should {

    // TODO (#6195): once the queries are implemented, write more succinct tests.
    "ingest" in {

      for {
        store <- mkStore()
        aContract = coin(2, userParty(0))
        _ <- dummyDomain.create(aContract)(store.multiDomainAcsStore)
      } yield {
        eventually() {
          store.multiDomainAcsStore
            .listContracts(Coin.COMPANION)
            .futureValue
            .map(_.contract) should be(Seq(aContract))
        }
      }

    }

  }

  protected def mkStore(): Future[ScanStore]

  private def coin(amount: Double, owner: PartyId) = {
    val template = new Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      expiringAmount(amount),
    )
    Contract(
      cc.coin.Coin.TEMPLATE_ID,
      new Coin.ContractId(s"${owner.toProtoPrimitive}#$amount"),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
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
      connection = null,
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
          "dar/canton-coin-0.1.0.dar"
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
      connection = null,
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
