package com.daml.network.store

import com.daml.network.store.db.AcsRowData
import com.digitalasset.canton.logging.NamedLogging

import scala.concurrent.ExecutionContext

/** Store setup shared by all of our apps
  */
trait CNNodeAppStore[TXE <: TxLogStore.Entry]
    extends NamedLogging
    with AutoCloseable
    with StoreErrors {

  implicit protected def ec: ExecutionContext

  /** Defines which create events are to be ingested into the store. */
  protected def acsContractFilter: MultiDomainAcsStore.ContractFilter[_ <: AcsRowData]

  /** Defines how to parse and serialize TxLog entries. */
  protected def txLogConfig: TxLogStore.Config[TXE]

  def domains: DomainStore

  def multiDomainAcsStore: MultiDomainAcsStore

}

/** A coin app store whose TxLog is always empty.
  */
trait CNNodeAppStoreWithoutHistory extends CNNodeAppStore[TxLogStore.Entry] {
  override final def txLogConfig = TxLogStore.Config.empty
}
