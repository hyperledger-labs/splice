package com.daml.network.store.db

import com.daml.network.codegen.java.cn.directory.{
  DirectoryEntry,
  DirectoryEntryContext,
  DirectoryInstall,
}
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.codegen.java.cn.wallet.subscriptions.{
  Subscription,
  SubscriptionData,
  SubscriptionIdleState,
  SubscriptionPayData,
  SubscriptionRequest,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.directory.store.DirectoryStore.IdleDirectorySubscription
import com.daml.network.directory.store.db.DbDirectoryStore
import com.daml.network.directory.store.memory.InMemoryDirectoryStore
import com.daml.network.environment.{DarResources, RetryProvider}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.{PageLimit, StoreTest}
import com.daml.network.util.{AssignedContract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}

import java.time.Instant
import scala.concurrent.Future

abstract class DirectoryStoreTest extends StoreTest with HasExecutionContext {
  val provider = providerParty(0)

  "DirectoryStore" should {

    "lookupInstallByUserWithOffset" should {

      "return nothing if no install exists" in {
        for {
          store <- mkStore()
          unwantedContract = directoryInstall(1)
          _ <- dummyDomain.create(unwantedContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
        } yield {
          eventually() {
            store.lookupInstallByUserWithOffset(userParty(2)).futureValue.value should be(
              None
            )
          }
        }
      }

      "return the install of the user" in {
        for {
          store <- mkStore()
          unwantedContract = directoryInstall(1)
          wantedContract = directoryInstall(2)
          _ <- dummyDomain.create(unwantedContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(wantedContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
        } yield {
          eventually() {
            store.lookupInstallByUserWithOffset(userParty(2)).futureValue.value should be(
              Some(wantedContract)
            )
          }
        }
      }

    }

    "lookupEntryByNameWithOffset" should {

      "return the offset + None for no entry" in {
        for {
          store <- mkStore()
          result <- store.lookupEntryByNameWithOffset("nope")
        } yield result should be(QueryResult(offset.toHexString, None))
      }

      "return the entry with the exact name" in {
        for {
          store <- mkStore()
          unwantedContract = directoryEntry(1, "unwanted")
          wantedContract = directoryEntry(2, "wanted")
          _ <- dummyDomain.create(unwantedContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(wantedContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
        } yield {
          eventually() {
            store.lookupEntryByNameWithOffset("wanted").futureValue.value should be(
              Some(wantedContract)
            )
          }
        }
      }

    }

    "listExpiredDirectorySubscriptions" should {

      "return all entries where subscription_next_payment_due_at < now" in {
        for {
          store <- mkStore()
          // 1 to 3 are expired, 4 to 6 are not
          data = ((1 to 3).map(n => n -> Instant.now().minusSeconds(n * 1000L)) ++ (4 to 6)
            .map(n => n -> Instant.now().plusSeconds(n * 1000L)))
            .map { case (n, nextPaymentDueAt) =>
              val contextContract =
                directoryEntryContext(n, n.toString)
              val idleStateContract =
                subscriptionIdleState(
                  n,
                  nextPaymentDueAt,
                )

              (contextContract, idleStateContract)
            }
          _ <- Future.traverse(data) { case (contextContract, idleContract) =>
            for {
              _ <- dummyDomain.create(contextContract, createdEventSignatories = Seq(provider))(
                store.multiDomainAcsStore
              )
              _ <- dummyDomain.create(idleContract, createdEventSignatories = Seq(provider))(
                store.multiDomainAcsStore
              )
            } yield ()
          }
        } yield {
          val expected = data
            .take(3)
            .map { case (ctxContract, idleContract) =>
              IdleDirectorySubscription(AssignedContract(idleContract, dummyDomain), ctxContract)
            }
            .reverse
          eventually() {
            store
              .listExpiredDirectorySubscriptions(CantonTimestamp.now(), PageLimit.tryCreate(3))
              .futureValue should be(expected)
          }
        }
      }

    }

    "lookupEntryByParty" should {

      "return the first lexicographical entry of the user" in {
        for {
          store <- mkStore()
          unwantedContract = directoryEntry(1, "unwanted")
          bContract = directoryEntry(2, "b")
          aContract = directoryEntry(2, "a")
          _ <- dummyDomain.create(unwantedContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(bContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(aContract, createdEventSignatories = Seq(provider))(
            store.multiDomainAcsStore
          )
        } yield {
          eventually() {
            store.lookupEntryByParty(userParty(2)).futureValue should be(Some(aContract))
          }
        }
      }

    }

  }

  private def directoryInstall(n: Int) = {
    val templateId = DirectoryInstall.TEMPLATE_ID
    val template = new DirectoryInstall(
      provider.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      svcParty.toProtoPrimitive,
      numeric(BigDecimal("1.0")),
      new RelTime(1_000_000_000),
      new RelTime(1_000_000_000),
    )
    contract(
      identifier = templateId,
      contractId = new DirectoryInstall.ContractId(s"$domain#$n"),
      payload = template,
    )
  }

  var cIdCounter = 0
  protected def directoryEntry(
      n: Int,
      name: String,
      cId: Int = cIdCounter,
      entryUrl: String = "https://cns-entry-url.com",
      entryDescription: String = "Sample fake description",
  ) = {
    cIdCounter += 1
    val templateId = DirectoryEntry.TEMPLATE_ID
    val template = new DirectoryEntry(
      userParty(n).toProtoPrimitive,
      provider.toProtoPrimitive,
      name,
      entryUrl,
      entryDescription,
      Instant.now().plusSeconds(3600),
    )
    contract(
      identifier = templateId,
      contractId = new DirectoryEntry.ContractId(s"$domain#$cId"),
      payload = template,
    )
  }

  private def directoryEntryContext(
      n: Int,
      name: String,
      entryUrl: String = "https://cns-entry-url.com",
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = DirectoryEntryContext.TEMPLATE_ID
    val template = new DirectoryEntryContext(
      svcParty.toProtoPrimitive,
      provider.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      name,
      entryUrl,
      entryDescription,
      new SubscriptionRequest.ContractId(validContractId(n, "ab")),
    )
    contract(
      identifier = templateId,
      contractId = new DirectoryEntryContext.ContractId(validContractId(n, "dc")),
      payload = template,
    )
  }

  private def subscriptionIdleState(
      n: Int,
      nextPaymentDueAt: Instant,
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = SubscriptionIdleState.TEMPLATE_ID
    val template = new SubscriptionIdleState(
      new Subscription.ContractId(validContractId(n, "aa")),
      new SubscriptionData(
        userParty(n).toProtoPrimitive,
        provider.toProtoPrimitive,
        provider.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        entryDescription,
      ),
      new SubscriptionPayData(
        new PaymentAmount(numeric(BigDecimal("1")), Currency.CC),
        new RelTime(1_000_000_000L),
        new RelTime(1_000_000L),
      ),
      nextPaymentDueAt,
      new SubscriptionRequest.ContractId(validContractId(n, "ab")),
    )
    contract(
      identifier = templateId,
      contractId = new SubscriptionIdleState.ContractId(s"$domain#$n"),
      payload = template,
    )
  }

  protected def mkStore(): Future[DirectoryStore]

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
}

class InMemoryDirectoryStoreTest extends DirectoryStoreTest {
  override protected def mkStore(): Future[InMemoryDirectoryStore] = {
    val store = new InMemoryDirectoryStore(
      provider,
      svcParty,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
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

class DbDirectoryStoreTest
    extends DirectoryStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(): Future[DbDirectoryStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.cantonCoin.all ++
          DarResources.directoryService.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbDirectoryStore(
      provider,
      svcParty,
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}
