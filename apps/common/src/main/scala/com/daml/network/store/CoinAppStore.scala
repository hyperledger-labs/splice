package com.daml.network.store

import com.digitalasset.canton.logging.NamedLogging

import scala.concurrent.ExecutionContext

/** Store setup shared by all of our apps
  */
trait CoinAppStore extends NamedLogging with AutoCloseable {
  implicit protected def ec: ExecutionContext

  def acs: AcsStore
  def domains: DomainStore

  def acsIngestionSink: AcsStore.IngestionSink
  def domainIngestionSink: DomainStore.IngestionSink
}
