package com.daml.network.store

import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** Mixin for [[CNNodeAppStore]]s that rely on an explicitly-configured default
  * ACS domain instead of deriving it from coin rules or SVC rules.  Eventually
  * no apps should use this; it's separated to allow apps to be fixed and these
  * definitions removed piecemeal.
  */
trait ConfiguredDefaultDomain { this: CNNodeAppStore[?, ?] =>
  def defaultAcsDomain: DomainAlias

  final def defaultAcsDomainIdF(implicit tc: TraceContext): Future[DomainId] =
    domains.signalWhenConnected(defaultAcsDomain)
}
