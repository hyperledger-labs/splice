package org.lfdecentralizedtrust.splice.store.db

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding as vo
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding.UsedSecret
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.StoreTest
import org.lfdecentralizedtrust.splice.sv.config.{
  SvDecentralizedSynchronizerConfig,
  SvSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.sv.store.db.DbSvSvStore
import org.lfdecentralizedtrust.splice.sv.store.{SvStore, SvSvStore}
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Future

abstract class SvSvStoreTest extends StoreTest with HasExecutionContext {

  "SvSvStore" should {

    "lookupValidatorOnboardingBySecretWithOffset" should {

      "find a ValidatorOnboarding by secret" in {
        val wanted = validatorOnboarding("good_secret")
        val unwanted = validatorOnboarding("bad_secret")
        val firstOffset = 101L
        val secondOffset = 202L
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
            QueryResult(Some(secondOffset), Some(wanted))
          )
          store.lookupValidatorOnboardingBySecretWithOffset("bad_secret").futureValue should be(
            QueryResult(Some(secondOffset), Some(unwanted))
          )
        }
      }

      "return just the offset if there's no entries" in {
        for {
          store <- mkStore()
          result <- store.lookupValidatorOnboardingBySecretWithOffset("whatever")
        } yield result should be(QueryResult(Some(acsOffset), None))
      }

    }

    "lookupValidatorOnboardingBySecretWithOffset" should {

      "find a UsedSecret by secret" in {
        val wanted = usedSecret("good_secret")
        val unwanted = usedSecret("bad_secret")
        val firstOffset = 101L
        val secondOffset = 202L
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
            QueryResult(Some(secondOffset), Some(wanted))
          )
          store.lookupUsedSecretWithOffset("bad_secret").futureValue should be(
            QueryResult(Some(secondOffset), Some(unwanted))
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
        Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
      )
    val templateId = vo.ValidatorOnboarding.TEMPLATE_ID_WITH_PACKAGE_ID

    contract(
      identifier = templateId,
      contractId = new vo.ValidatorOnboarding.ContractId(nextCid()),
      payload = template,
    )
  }

  private def usedSecret(secret: String) = {
    val template =
      new UsedSecret(storeSvParty.toProtoPrimitive, secret, storeSvParty.toProtoPrimitive)

    val templateId = vo.UsedSecret.TEMPLATE_ID_WITH_PACKAGE_ID

    contract(
      templateId,
      new UsedSecret.ContractId(nextCid()),
      template,
    )
  }

  protected def mkStore(): Future[SvSvStore]

  lazy val acsOffset = nextOffset()
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val storeSvParty = providerParty(42)
  lazy val svSynchronizerConfig = SvSynchronizerConfig(
    SvDecentralizedSynchronizerConfig(DomainAlias.tryCreate(domain), "https://example.com")
  )
}

class DbSvSvStoreTest
    extends SvSvStoreTest
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbSvSvStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.dsoGovernance.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbSvSvStore(
      SvStore.Key(storeSvParty, dsoParty),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(
        0,
        None,
      ),
      participantId = mkParticipantId("SvSvStoreTest"),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(Some(acsOffset), Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
}
