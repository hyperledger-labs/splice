package com.daml.network.unit.wallet

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.store.db.{AcsJdbcTypes, CNPostgresTest}
import com.daml.network.wallet.store.db.WalletTables
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.resource.DbStorage
import io.circe.Json
import org.scalatest.wordspec.AsyncWordSpec
import slick.jdbc.JdbcProfile

import java.time.Duration
import scala.concurrent.Future

class WalletPersistenceTest
    extends AsyncWordSpec
    with AcsJdbcTypes
    with BaseTest
    with CNPostgresTest {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile
  import storage.api.jdbcProfile.api.*

  val AcsTable = WalletTables.UserWalletAcsStore
  val TxLogTable = WalletTables.UserWalletTxLogStore
  val StoreDescriptorsTable = WalletTables.StoreDescriptors

  "UserWallet" should {

    "use storage" in {
      val descRow = WalletTables.StoreDescriptorsRow(
        id = 0,
        descriptor = Json.obj(
          "name" -> Json.fromString("WalletPersistenceTest")
        ),
        lastIngestedOffset = None,
      )
      for {
        _ <- storage.queryAndUpdate(
          insertRowIfNotExists(StoreDescriptorsTable)(
            _.descriptor === descRow.descriptor,
            descRow,
          ),
          "insert1",
        )
        fetchedDescRow <- storage.query(StoreDescriptorsTable.result, "fetch1").map(_.loneElement)

        acsRow = WalletTables.UserWalletAcsStoreRow(
          storeId = fetchedDescRow.id,
          eventNumber = 0,
          contractId = new ContractId(
            "003daad4665efc696dfb505d8ca794034a18f264cda4ebd3f0549f03c0f1f4ef42ca011220df05a9d3d5a4180cec940aeed16e1f080e6f81ee3867ff433e4a37ce2a9769c1"
          ),
          templateId = TemplateId(
            "bf196cb0db2637fd30850500c50984c3b2dc23c2f89b42d9a673c5dcad3649a2",
            "CN.Directory",
            "DirectoryEntry",
          ),
          createArguments = Json.obj(
            "a" -> Json.fromInt(1),
            "b" -> Json.arr(Json.fromString("c"), Json.fromString("d")),
          ),
          contractMetadataCreatedAt = Timestamp.now(),
          contractMetadataContractKeyHash = Some("0123456789"),
          contractMetadataDriverInternal = Array(255.toByte, 0.toByte, 255.toByte, 0.toByte),
          contractExpiresAt = Some(Timestamp.Epoch.add(Duration.ofDays(2))),
        )
        _ <- storage.queryAndUpdate(
          insertRowIfNotExists(AcsTable)(
            _.contractId === acsRow.contractId,
            acsRow.copy(storeId = fetchedDescRow.id),
          ),
          "insert2",
        )
        fetchedAcsRow <- storage.query(AcsTable.result, "fetch2").map(_.loneElement)

        txLogRow = WalletTables.UserWalletTxLogStoreRow(
          storeId = fetchedDescRow.id,
          entryNumber = 0,
          eventId = "someEventId",
        )
        _ <- storage.queryAndUpdate(
          insertRowIfNotExists(TxLogTable)(
            _.eventId === txLogRow.eventId,
            txLogRow,
          ),
          "insert3",
        )
        fetchedTxLogRow <- storage.query(TxLogTable.result, "fetch3").map(_.loneElement)
      } yield {
        fetchedDescRow should be(descRow.copy(id = fetchedDescRow.id))

        // Byte arrays are compared by reference
        fetchedAcsRow should be(
          acsRow.copy(
            eventNumber = fetchedAcsRow.eventNumber,
            contractMetadataDriverInternal = fetchedAcsRow.contractMetadataDriverInternal,
          )
        )

        fetchedTxLogRow should be(txLogRow.copy(entryNumber = fetchedTxLogRow.entryNumber))
      }
    }

  }

  override protected def cleanDb(x: DbStorage): Future[?] = {
    for {
      _ <- resetAllCnAppTables(x)
    } yield ()
  }
}
