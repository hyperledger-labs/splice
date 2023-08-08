package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cn.svcrules.SvReward
import com.daml.network.environment.RetryProvider
import com.daml.network.store.StoreTest
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.store.db.DbSvSvcStore
import com.daml.network.sv.store.memory.InMemorySvSvcStore
import com.daml.network.sv.store.{SvStore, SvSvcStore}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

import scala.concurrent.Future

abstract class SvSvcStoreTest extends StoreTest with HasExecutionContext {

  "SvSvcStore" should {

    // TODO (#6799): once the queries are implemented, write more succinct tests.
    "ingest" in {

      for {
        store <- mkStore()
        aContract = svReward()
        _ <- dummyDomain.create(aContract)(store.multiDomainAcsStore)
      } yield {
        eventually() {
          store.multiDomainAcsStore
            .listContracts(SvReward.COMPANION)
            .futureValue
            .map(_.contract) should be(Seq(aContract))
        }
      }

    }

  }

  private def svReward() = {
    val template = new SvReward(
      svcParty.toProtoPrimitive,
      storeSvParty.toProtoPrimitive,
      new Round(1),
      numeric(1.0),
    )

    Contract(
      SvReward.TEMPLATE_ID,
      new SvReward.ContractId(validContractId(1)),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  protected def mkStore(): Future[SvSvcStore]

  lazy val acsOffset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val storeSvParty = providerParty(42)
  lazy val svDomainConfig = SvDomainConfig(
    SvGlobalDomainConfig(DomainAlias.tryCreate(domain), "https://example.com")
  )
}

class InMemorySvSvcStoreTest extends SvSvcStoreTest {
  override protected def mkStore(): Future[InMemorySvSvcStore] = {
    val store = new InMemorySvSvcStore(
      SvStore.Key(storeSvParty, svcParty),
      svDomainConfig,
      enableCoinRulesUpgrade = true,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(acsOffset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }
}

class DbSvSvcStoreTest
    extends SvSvcStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbSvSvcStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        Seq(
          "dar/canton-coin-0.1.1.dar",
          "dar/validator-lifecycle-0.1.0.dar",
          "dar/svc-governance-0.1.0.dar",
        )
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbSvSvcStore(
      SvStore.Key(storeSvParty, svcParty),
      storage,
      svDomainConfig,
      enableCoinRulesUpgrade = true,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(acsOffset.toHexString, Seq.empty, Seq.empty, Seq.empty)
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
