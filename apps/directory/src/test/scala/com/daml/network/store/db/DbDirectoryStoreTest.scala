// TODO (#5549): make abstract and base both DbDirectoryStoreTest and InMemoryDirectoryStoreTest on it
package com.daml.network.store.db

import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
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
import com.daml.network.directory.store.db.DirectoryTables.*
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.StoreTest
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
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

  import storage.api.jdbcProfile.api.*

  "DbDirectoryStore" should {

    "lookupInstallByUserWithOffset" should {

      "return the install of the user" in {
        for {
          store <- mkStore()
          (_, unwantedRow) = directoryInstall(store.storeId, 1)
          (wantedContract, wantedRow) = directoryInstall(store.storeId, 2)
          _ <- storage
            .queryAndUpdate(
              insertRowIfNotExists(DirectoryAcsStore)(
                _.eventNumber === unwantedRow.eventNumber,
                unwantedRow,
              ).andThen(
                insertRowIfNotExists(DirectoryAcsStore)(
                  _.eventNumber === wantedRow.eventNumber,
                  wantedRow,
                )
              ),
              "inserts",
            )
          result <- store.lookupInstallByUserWithOffset(userParty(2))
        } yield {
          result.value should be(Some(wantedContract))
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
          (_, unwantedRow) = directoryEntry(store.storeId, 1, "unwanted")
          (wantedContract, wantedRow) = directoryEntry(store.storeId, 2, "wanted")
          _ <- storage
            .queryAndUpdate(
              insertRowIfNotExists(DirectoryAcsStore)(
                _.eventNumber === unwantedRow.eventNumber,
                unwantedRow,
              ).andThen(
                insertRowIfNotExists(DirectoryAcsStore)(
                  _.eventNumber === wantedRow.eventNumber,
                  wantedRow,
                )
              ),
              "inserts",
            )
          result <- store.lookupEntryByNameWithOffset("wanted")
        } yield {
          result.value should be(Some(wantedContract))
        }
      }

    }

    "lookupEntryByParty" should {

      "return the first lexicographical entry of the user" in {
        for {
          store <- mkStore()
          (_, unwantedRow) = directoryEntry(store.storeId, 1, "unwanted")
          (aContract, aRow) = directoryEntry(store.storeId, 2, "a")
          (_, bRow) = directoryEntry(store.storeId, 2, "b")
          _ <- storage
            .queryAndUpdate(
              DBIO.sequence(
                Seq(unwantedRow, aRow, bRow).map(row =>
                  insertRowIfNotExists(DirectoryAcsStore)(
                    _.eventNumber === row.eventNumber,
                    row,
                  )
                )
              ),
              "inserts",
            )
          result <- store.lookupEntryByParty(userParty(2))
        } yield result should be(Some(aContract))
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
              val (contextContract, contextRow) =
                directoryEntryContext(store.storeId, n, n.toString)
              val (idleStateContract, idleStateRow) =
                subscriptionIdleState(
                  store.storeId,
                  n,
                  nextPaymentDueAt,
                  contextContract.contractId,
                )

              (contextContract, contextRow, idleStateContract, idleStateRow)
            }
          _ <- storage
            .queryAndUpdate(
              DBIO.sequence(
                data
                  .flatMap { case (_, ctxRow, _, idleRow) => Seq(ctxRow, idleRow) }
                  .map(row =>
                    insertRowIfNotExists(DirectoryAcsStore)(
                      _.contractId === row.contractId,
                      row,
                    )
                  )
              ),
              "inserts",
            )
          result <- store.listExpiredDirectorySubscriptions(CantonTimestamp.now(), limit = 3)
        } yield {
          val expected = data
            .take(3)
            .map { case (ctxContract, _, idleContract, _) =>
              IdleDirectorySubscription(idleContract, ctxContract)
            }
            .reverse
          result should be(expected)
        }
      }

    }

  }

  private def directoryInstall(storeId: Int, n: Int) = {
    val templateId = DirectoryInstall.TEMPLATE_ID
    val template = new DirectoryInstall(
      svcParty.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      svcParty.toProtoPrimitive,
      numeric(BigDecimal("1.0")),
      new RelTime(1_000_000_000),
      new RelTime(1_000_000_000),
    )
    val contract = Contract(
      identifier = templateId,
      contractId = new DirectoryInstall.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
    val row = DirectoryAcsStoreRow(
      storeId = storeId,
      eventNumber = n.toLong,
      contractId = contract.contractId.asInstanceOf[ContractId[Any]],
      templateId = TemplateId.fromIdentifier(
        Identifier.of(
          templateId.getPackageId,
          templateId.getModuleName,
          templateId.getEntityName,
        )
      ),
      createArguments = contract.toJson.payload,
      contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt),
      contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray,
      directoryInstallUser = Some(userParty(n)),
    )
    (contract, row)
  }

  private def directoryEntry(storeId: Int, n: Int, name: String) = {
    val templateId = DirectoryEntry.TEMPLATE_ID
    val template = new DirectoryEntry(
      userParty(n).toProtoPrimitive,
      svcParty.toProtoPrimitive,
      name,
      Instant.now().plusSeconds(3600),
    )
    val contract = Contract(
      identifier = templateId,
      contractId = new DirectoryEntry.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
    val row = DirectoryAcsStoreRow(
      storeId = storeId,
      eventNumber = n.toLong,
      contractId = contract.contractId.asInstanceOf[ContractId[Any]],
      templateId = TemplateId.fromIdentifier(
        Identifier.of(
          templateId.getPackageId,
          templateId.getModuleName,
          templateId.getEntityName,
        )
      ),
      createArguments = contract.toJson.payload,
      contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt),
      contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray,
      directoryEntryOwner = Some(userParty(n)),
      directoryEntryName = Some(name),
    )
    (contract, row)
  }

  private def directoryEntryContext(storeId: Int, n: Int, name: String) = {
    val templateId = DirectoryEntryContext.TEMPLATE_ID
    val template = new DirectoryEntryContext(
      svcParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      userParty(n).toProtoPrimitive,
      name,
    )
    val contract = Contract(
      identifier = templateId,
      contractId = new DirectoryEntryContext.ContractId(validContractId(n, "dc")),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
    val row = DirectoryAcsStoreRow(
      storeId = storeId,
      eventNumber = n.toLong,
      contractId = contract.contractId.asInstanceOf[ContractId[Any]],
      templateId = TemplateId.fromIdentifier(
        Identifier.of(
          templateId.getPackageId,
          templateId.getModuleName,
          templateId.getEntityName,
        )
      ),
      createArguments = contract.toJson.payload,
      contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt),
      contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray,
      directoryEntryOwner = Some(userParty(n)),
      directoryEntryName = Some(name),
    )
    (contract, row)
  }

  private def subscriptionIdleState(
      storeId: Int,
      n: Int,
      nextPaymentDueAt: Instant,
      directoryEntryContextCid: DirectoryEntryContext.ContractId,
  ) = {
    val templateId = SubscriptionIdleState.TEMPLATE_ID
    val template = new SubscriptionIdleState(
      new Subscription.ContractId(validContractId(n, "aa")),
      new Subscription(
        userParty(n).toProtoPrimitive,
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
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
    val contract = Contract(
      identifier = templateId,
      contractId = new SubscriptionIdleState.ContractId(s"$domain#$n"),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
    val row = DirectoryAcsStoreRow(
      storeId = storeId,
      eventNumber = n.toLong,
      contractId = contract.contractId.asInstanceOf[ContractId[Any]],
      templateId = TemplateId.fromIdentifier(
        Identifier.of(
          templateId.getPackageId,
          templateId.getModuleName,
          templateId.getEntityName,
        )
      ),
      createArguments = contract.toJson.payload,
      contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt),
      contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray,
      subscriptionContextContractId =
        Some(directoryEntryContextCid.toInterface(SubscriptionContext.INTERFACE)),
      subscriptionNextPaymentDueAt = Some(Timestamp.assertFromInstant(nextPaymentDueAt)),
    )
    (contract, row)
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
      PartyId.tryFromProtoPrimitive(s"${domain}::provider"),
      PartyId.tryFromProtoPrimitive(s"${domain}::svc"),
      storage,
      GlobalOnlyDomainConfig(DomainConfig(DomainAlias.tryCreate(domain))),
      loggerFactory,
      FutureSupervisor.Noop,
      RetryProvider(loggerFactory, timeouts),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty)
    } yield store
  }

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  val domain = "domain"

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}
