// TODO (#5549): make abstract and base both DbDirectoryStoreTest and InMemoryDirectoryStoreTest on it
package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cn.directory.{
  DirectoryEntry,
  DirectoryEntryContext,
  DirectoryInstall,
}
import com.daml.network.codegen.java.cn.wallet.payment.{Currency, PaymentAmount}
import com.daml.network.codegen.java.cn.wallet.subscriptions.{
  Subscription,
  SubscriptionContext,
  SubscriptionIdleState,
  SubscriptionPayData,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.config.{DomainConfig, GlobalOnlyDomainConfig}
import com.daml.network.directory.store.DirectoryStore.IdleDirectorySubscription
import com.daml.network.directory.store.db.DbDirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.StoreTest
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.{DomainAlias, HasActorSystem}
import com.google.protobuf

import java.time.Instant
import scala.concurrent.Future

class DbDirectoryStoreTest
    extends StoreTest
    with CNPostgresTest
    with HasActorSystem
    with AcsJdbcTypes
    with AcsTables {

  "DbDirectoryStore" should {

    "lookupInstallByUserWithOffset" should {

      "return the install of the user" in {
        for {
          store <- mkStore()
          unwantedContract = directoryInstall(1)
          wantedContract = directoryInstall(2)
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
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
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store.lookupEntryByNameWithOffset("wanted").futureValue.value should be(
              Some(wantedContract)
            )
          }
        }
      }

    }

    "lookupEntryByParty" should {

      "return the first lexicographical entry of the user" in {
        for {
          store <- mkStore()
          unwantedContract = directoryEntry(1, "unwanted")
          aContract = directoryEntry(2, "a")
          bContract = directoryEntry(2, "b")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(aContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(bContract)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store.lookupEntryByParty(userParty(2)).futureValue should be(Some(aContract))
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
                  contextContract.contractId,
                )

              (contextContract, idleStateContract)
            }
          _ <- Future.traverse(data) { case (contextContract, idleContract) =>
            for {
              _ <- dummyDomain.create(contextContract)(store.multiDomainAcsStore)
              _ <- dummyDomain.create(idleContract)(store.multiDomainAcsStore)
            } yield ()
          }
        } yield {
          val expected = data
            .take(3)
            .map { case (ctxContract, idleContract) =>
              IdleDirectorySubscription(idleContract, ctxContract)
            }
            .reverse
          eventually() {
            store
              .listExpiredDirectorySubscriptions(CantonTimestamp.now(), limit = 3)
              .futureValue should be(expected)
          }
        }
      }

    }

  }

  val provider = providerParty(0)

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
    Contract(
      identifier = templateId,
      contractId = new DirectoryInstall.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def directoryEntry(n: Int, name: String) = {
    val templateId = DirectoryEntry.TEMPLATE_ID
    val template = new DirectoryEntry(
      userParty(n).toProtoPrimitive,
      provider.toProtoPrimitive,
      name,
      Instant.now().plusSeconds(3600),
    )
    Contract(
      identifier = templateId,
      contractId = new DirectoryEntry.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def directoryEntryContext(n: Int, name: String) = {
    val templateId = DirectoryEntryContext.TEMPLATE_ID
    val template = new DirectoryEntryContext(
      svcParty.toProtoPrimitive,
      provider.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      name,
    )
    Contract(
      identifier = templateId,
      contractId = new DirectoryEntryContext.ContractId(validContractId(n, "dc")),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def subscriptionIdleState(
      n: Int,
      nextPaymentDueAt: Instant,
      directoryEntryContextCid: DirectoryEntryContext.ContractId,
  ) = {
    val templateId = SubscriptionIdleState.TEMPLATE_ID
    val template = new SubscriptionIdleState(
      new Subscription.ContractId(validContractId(n, "aa")),
      new Subscription(
        userParty(n).toProtoPrimitive,
        provider.toProtoPrimitive,
        provider.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        directoryEntryContextCid.toInterface(SubscriptionContext.INTERFACE),
      ),
      new SubscriptionPayData(
        new PaymentAmount(numeric(BigDecimal("1")), Currency.CC),
        new RelTime(1_000_000_000L),
        new RelTime(1_000_000L),
      ),
      nextPaymentDueAt,
    )
    Contract(
      identifier = templateId,
      contractId = new SubscriptionIdleState.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def mkStore(): Future[DbDirectoryStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        Seq(
          "dar/canton-coin-0.1.0.dar",
          "dar/directory-service-0.1.0.dar",
        )
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbDirectoryStore(
      provider,
      svcParty,
      storage,
      GlobalOnlyDomainConfig(DomainConfig(DomainAlias.tryCreate(domain))),
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty)
    } yield store
  }

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}
