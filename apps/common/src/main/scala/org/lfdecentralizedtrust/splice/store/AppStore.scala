// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.store.db.{AcsInterfaceViewRowData, AcsRowData}
import com.digitalasset.canton.logging.NamedLogging

import scala.concurrent.ExecutionContext

/** Store setup shared by all of our apps
  */
trait AppStore extends NamedLogging with AutoCloseable with StoreErrors {

  val storeName: String

  implicit protected def ec: ExecutionContext

  /** Defines which create events are to be ingested into the store. */
  def acsContractFilter
      : MultiDomainAcsStore.ContractFilter[? <: AcsRowData, ? <: AcsInterfaceViewRowData]

  def domains: SynchronizerStore

  def multiDomainAcsStore: MultiDomainAcsStore

}

trait TxLogAppStore[TXE] extends AppStore {

  /** Defines how to parse and serialize TxLog entries. */
  def txLogConfig: TxLogStore.Config[TXE]
}
