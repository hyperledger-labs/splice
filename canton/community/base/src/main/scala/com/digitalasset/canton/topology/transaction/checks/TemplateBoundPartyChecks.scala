// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.transaction.checks

import cats.data.EitherT
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.topology.processing.EffectiveTime
import com.digitalasset.canton.topology.store.TopologyTransactionRejection
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.topology.transaction.TemplateBoundPartyMapping
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.ExecutionContext

class TemplateBoundPartyChecks(implicit ec: ExecutionContext) extends TopologyMappingChecks {

  override def checkTransaction(
      effective: EffectiveTime,
      toValidate: GenericSignedTopologyTransaction,
      inStore: Option[GenericSignedTopologyTransaction],
      relaxChecksForBackwardsCompatibility: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TopologyTransactionRejection, Unit] = {
    toValidate.mapping match {
      case _: TemplateBoundPartyMapping =>
        inStore match {
          case Some(_) =>
            EitherT.leftT[FutureUnlessShutdown, Unit](
              TopologyTransactionRejection.RequiredMapping.InvalidTopologyMapping(
                "Template-bound party configurations are immutable. " +
                  "The allowed template set cannot be modified after creation. " +
                  "Create a new template-bound party if different templates are needed."
              )
            )
          case None =>
            EitherT.rightT[FutureUnlessShutdown, TopologyTransactionRejection](())
        }
      case _ =>
        EitherT.rightT[FutureUnlessShutdown, TopologyTransactionRejection](())
    }
  }
}
