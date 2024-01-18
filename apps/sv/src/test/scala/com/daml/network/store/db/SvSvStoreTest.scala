package com.daml.network.store.db

import com.daml.network.codegen.java.cn.svlocal.approvedsvidentity.ApprovedSvIdentity
import com.daml.network.codegen.java.cn.validatoronboarding as vo
import com.daml.network.codegen.java.cn.validatoronboarding.UsedSecret
import com.daml.network.environment.{DarResources, RetryProvider}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.StoreTest
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.store.db.DbSvSvStore
import com.daml.network.sv.store.memory.InMemorySvSvStore
import com.daml.network.sv.store.{SvStore, SvSvStore}
import com.daml.network.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}

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
          _ <- dummyDomain.create(wanted, firstOffset, createdEventSignatories = Seq(storeSvParty))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            unwanted,
            secondOffset,
            createdEventSignatories = Seq(storeSvParty),
          )(store.multiDomainAcsStore)
        } yield {
          store.lookupValidatorOnboardingBySecretWithOffset("good_secret").futureValue should be(
            QueryResult(secondOffset, Some(wanted))
          )
          store.lookupValidatorOnboardingBySecretWithOffset("bad_secret").futureValue should be(
            QueryResult(secondOffset, Some(unwanted))
          )
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
          _ <- dummyDomain.create(wanted, firstOffset, createdEventSignatories = Seq(storeSvParty))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            unwanted,
            secondOffset,
            createdEventSignatories = Seq(storeSvParty),
          )(store.multiDomainAcsStore)
        } yield {
          store.lookupUsedSecretWithOffset("good_secret").futureValue should be(
            QueryResult(secondOffset, Some(wanted))
          )
          store.lookupUsedSecretWithOffset("bad_secret").futureValue should be(
            QueryResult(secondOffset, Some(unwanted))
          )
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
          _ <- dummyDomain.create(wanted, firstOffset, createdEventSignatories = Seq(storeSvParty))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            unwanted,
            secondOffset,
            createdEventSignatories = Seq(storeSvParty),
          )(store.multiDomainAcsStore)
        } yield {
          store.lookupApprovedSvIdentityByNameWithOffset("good_name").futureValue should be(
            QueryResult(secondOffset, Some(wanted))
          )
          store.lookupApprovedSvIdentityByNameWithOffset("bad_name").futureValue should be(
            QueryResult(secondOffset, Some(unwanted))
          )
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

    contract(
      identifier = templateId,
      contractId = new vo.ValidatorOnboarding.ContractId(nextCid()),
      payload = template,
    )
  }

  private def usedSecret(secret: String) = {
    val template =
      new UsedSecret(storeSvParty.toProtoPrimitive, secret, storeSvParty.toProtoPrimitive)

    val templateId = vo.UsedSecret.TEMPLATE_ID

    contract(
      templateId,
      new UsedSecret.ContractId(nextCid()),
      template,
    )
  }

  private def approvedSvIdentity(name: String) = {
    val template = new ApprovedSvIdentity(storeSvParty.toProtoPrimitive, name, "some key")

    contract(
      ApprovedSvIdentity.TEMPLATE_ID,
      new ApprovedSvIdentity.ContractId(nextCid()),
      template,
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
        DarResources.cantonCoin.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.svcGovernance.all ++
          DarResources.svcGovernance.all ++
          DarResources.svLocal.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbSvSvStore(
      SvStore.Key(storeSvParty, svcParty),
      storage,
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
