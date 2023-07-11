package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.environment.RetryProvider
import com.daml.network.store.StoreTest
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.store.db.DbSvSvStore
import com.daml.network.sv.store.memory.InMemorySvSvStore
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

import java.time.Instant
import scala.concurrent.Future

abstract class SvSvStoreTest extends StoreTest with HasExecutionContext {

  "SvSvStore" should {

    // TODO (#6443): once the queries are implemented, write more succinct tests.
    "ingest" in {

      for {
        store <- mkStore()
        aContract = validatorOnboarding()
        _ <- dummyDomain.create(aContract)(store.multiDomainAcsStore)
      } yield {
        eventually() {
          store.multiDomainAcsStore
            .listContracts(vo.ValidatorOnboarding.COMPANION)
            .futureValue
            .map(_.contract) should be(Seq(aContract))
        }
      }

    }

  }

  private def validatorOnboarding() = {
    val template =
      new vo.ValidatorOnboarding(
        storeSvParty.toProtoPrimitive,
        "supersecret",
        Instant.now().plusSeconds(3600),
      )
    val templateId = vo.ValidatorOnboarding.TEMPLATE_ID

    Contract(
      identifier = templateId,
      contractId = new vo.ValidatorOnboarding.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  protected def mkStore(): Future[SvSvStore]

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val storeSvParty = providerParty(42)
  lazy val svDomainConfig = SvDomainConfig(
    SvGlobalDomainConfig(DomainAlias.tryCreate(domain), "https://example.com")
  )
}

class InMemorySvSvStoreTest extends SvSvStoreTest {
  override protected def mkStore(): Future[InMemorySvSvStore] = {
    val store = new InMemorySvSvStore(
      SvStore.Key(storeSvParty, svcParty),
      svDomainConfig,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }
}

class DbSvSvStoreTest
    extends SvSvStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbSvSvStore] = {
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

    val store = new DbSvSvStore(
      SvStore.Key(storeSvParty, svcParty),
      storage,
      svDomainConfig,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
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
