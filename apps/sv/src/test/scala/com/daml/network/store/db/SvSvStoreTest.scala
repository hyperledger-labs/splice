package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cn.svonboarding.ApprovedSvIdentity
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.codegen.java.cn.validatoronboarding.UsedSecret
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.StoreTest
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.store.db.DbSvSvStore
import com.daml.network.sv.store.memory.InMemorySvSvStore
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf

import java.time.Instant
import scala.concurrent.Future

abstract class SvSvStoreTest extends StoreTest with HasExecutionContext {

  "SvSvStore" should {

    "lookupValidatorOnboardingBySecretWithOffset" should {

      "find a ValidatorOnboarding by secret" in {
        val wanted = validatorOnboarding("good_secret")
        val unwanted = validatorOnboarding("bad_secret")
        val firstOffset = "0101"
        val secondOffset = "0202"
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted, firstOffset)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted, secondOffset)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store.lookupValidatorOnboardingBySecretWithOffset("good_secret").futureValue should be(
              QueryResult(secondOffset, Some(wanted))
            )
          }
        }
      }

      "return just the offset if there's no entries" in {
        for {
          store <- mkStore()
          result <- store.lookupValidatorOnboardingBySecretWithOffset("whatever")
        } yield result should be(QueryResult(acsOffset.toHexString, None))
      }

    }

    "lookupValidatorOnboardingBySecretWithOffset" should {

      "find a UsedSecret by secret" in {
        val wanted = usedSecret("good_secret")
        val unwanted = usedSecret("bad_secret")
        val firstOffset = "0101"
        val secondOffset = "0202"
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted, firstOffset)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted, secondOffset)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store.lookupUsedSecretWithOffset("good_secret").futureValue should be(
              QueryResult(secondOffset, Some(wanted))
            )
          }
        }
      }

    }

    "lookupApprovedSvIdentityByNameWithOffset" should {

      "find an ApprovedSvIdentity by name" in {
        val wanted = approvedSvIdentity("good_name")
        val unwanted = approvedSvIdentity("bad_name")
        val firstOffset = "0101"
        val secondOffset = "0202"
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted, firstOffset)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted, secondOffset)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store.lookupApprovedSvIdentityByNameWithOffset("good_name").futureValue should be(
              QueryResult(secondOffset, Some(wanted))
            )
          }
        }
      }

    }

  }

  private def validatorOnboarding(secret: String) = {
    val template =
      new vo.ValidatorOnboarding(
        storeSvParty.toProtoPrimitive,
        secret,
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

  private def usedSecret(secret: String) = {
    val template =
      new UsedSecret(storeSvParty.toProtoPrimitive, secret, storeSvParty.toProtoPrimitive)

    val templateId = vo.UsedSecret.TEMPLATE_ID

    Contract(
      templateId,
      new UsedSecret.ContractId(validContractId(1)),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def approvedSvIdentity(name: String) = {
    val template = new ApprovedSvIdentity(storeSvParty.toProtoPrimitive, name, "some key")

    Contract(
      ApprovedSvIdentity.TEMPLATE_ID,
      new ApprovedSvIdentity.ContractId(validContractId(1)),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  protected def mkStore(): Future[SvSvStore]

  lazy val acsOffset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
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
