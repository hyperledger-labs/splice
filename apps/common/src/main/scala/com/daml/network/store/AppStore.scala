// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import com.daml.network.store.db.AcsRowData
import com.digitalasset.canton.logging.NamedLogging

import scala.concurrent.ExecutionContext

/** Store setup shared by all of our apps
  */
trait AppStore extends NamedLogging with AutoCloseable with StoreErrors {

  implicit protected def ec: ExecutionContext

  /** Defines which create events are to be ingested into the store. */
  protected def acsContractFilter: MultiDomainAcsStore.ContractFilter[_ <: AcsRowData]

  def domains: DomainStore

  def multiDomainAcsStore: MultiDomainAcsStore

  def updateHistory: UpdateHistory
}

trait TxLogAppStore[TXE] extends AppStore {

  /** Defines how to parse and serialize TxLog entries. */
  protected def txLogConfig: TxLogStore.Config[TXE]
}
