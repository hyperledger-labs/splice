package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.store.{StoreErrors, TxLogStore}
import com.daml.network.store.db.TxLogQueries.{
  SelectFromTxLogTableResult,
  SelectFromTxLogTableResultWithOffset,
}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import slick.jdbc.{GetResult, PositionedResult}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.DomainId
import slick.jdbc.canton.SQLActionBuilder

import scala.reflect.ClassTag

trait TxLogQueries[TXE] extends AcsJdbcTypes with StoreErrors {

  /** @param tableName Must be SQL-safe, as it needs to be interpolated unsafely.
    *                   This is fine, as all calls to this method should use static string constants.
    */
  protected def selectFromTxLogTable(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select #${SelectFromTxLogTableResult.sqlColumnsCommaSeparated()}
       from #$tableName
       where store_id = $storeId and """ ++ where ++ sql" " ++
      orderLimit).toActionBuilder
      .as[TxLogQueries.SelectFromTxLogTableResult]

  implicit val GetResultSelectFromTxLogTable: GetResult[TxLogQueries.SelectFromTxLogTableResult] =
    GetResult { prs =>
      import prs.*
      (TxLogQueries.SelectFromTxLogTableResult.apply _).tupled(
        (
          <<[Int],
          <<[Long],
          <<[String],
          <<[DomainId],
          <<[Option[ContractId[?]]],
          <<[String3],
          <<[String],
        )
      )
    }

  /** Same as [[selectFromAcsTableWithOffset]], but for tx log tables.
    */
  protected def selectFromTxLogTableWithOffset(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""select #${SelectFromTxLogTableResultWithOffset.sqlColumnsCommaSeparated()}
       from store_descriptors sd
           left join #$tableName
               on sd.id = store_id
               and """ ++ where ++ sql"""
       where sd.id = $storeId
       """ ++ orderLimit).toActionBuilder.as[TxLogQueries.SelectFromTxLogTableResultWithOffset]

  implicit val GetResultSelectFromTxLogTableWithOffset
      : GetResult[TxLogQueries.SelectFromTxLogTableResultWithOffset] = { (pp: PositionedResult) =>
    val storeIdFromTxLogRow = pp.<<[Option[Int]]
    TxLogQueries.SelectFromTxLogTableResultWithOffset(
      pp.<<,
      storeIdFromTxLogRow.map { storeId =>
        SelectFromTxLogTableResult(
          storeId,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
        )
      },
    )
  }

  protected def txLogEntryFromRow[TXER <: TXE](
      config: TxLogStore.Config[TXE]
  )(row: SelectFromTxLogTableResult)(implicit tag: ClassTag[TXER]): TXER = {
    config.decodeEntry(row.entryType, row.entryData) match {
      case e: TXER => e
      case _ => throw txLogIsOfWrongType()
    }
  }
}

object TxLogQueries {
  case class SelectFromTxLogTableResult(
      storeId: Int,
      entryNumber: Long,
      offset: String,
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      entryType: String3,
      entryData: String,
  )

  object SelectFromTxLogTableResult {
    def sqlColumnsCommaSeparated(qualifier: String = "") =
      s"""${qualifier}store_id,
          ${qualifier}entry_number,
          ${qualifier}transaction_offset,
          ${qualifier}domain_id,
          ${qualifier}acs_contract_id,
          ${qualifier}entry_type,
          ${qualifier}entry_data"""
  }

  case class SelectFromTxLogTableResultWithOffset(
      offset: String,
      row: Option[SelectFromTxLogTableResult],
  )

  object SelectFromTxLogTableResultWithOffset {
    def sqlColumnsCommaSeparated(qualifier: String = "") =
      s"""${qualifier}store_id,
          ${qualifier}last_ingested_offset,
          ${qualifier}entry_number,
          ${qualifier}transaction_offset,
          ${qualifier}domain_id,
          ${qualifier}acs_contract_id,
          ${qualifier}entry_type,
          ${qualifier}entry_data"""
  }

}
