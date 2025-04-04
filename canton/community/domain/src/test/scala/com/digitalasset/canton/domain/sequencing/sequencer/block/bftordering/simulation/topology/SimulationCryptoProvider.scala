// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.simulation.topology

import cats.syntax.either.*
import com.digitalasset.canton.crypto.{
  Hash,
  HashPurpose,
  Signature,
  SignatureCheckError,
  SyncCryptoError,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.topology.CryptoProvider
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.{
  MessageFrom,
  SignedMessage,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.simulation.SimulationModuleSystem.SimulationEnv
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.simulation.future.SimulationFuture
import com.digitalasset.canton.serialization.ProtocolVersionedMemoizedEvidence
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext

import scala.util.Try

// TODO(#22242) Use [[SymbolicPureCrypto]] here
object SimulationCryptoProvider extends CryptoProvider[SimulationEnv] {

  override def sign(hash: Hash)(implicit
      traceContext: TraceContext
  ): SimulationFuture[Either[SyncCryptoError, Signature]] = SimulationFuture { () =>
    Try {
      Right(Signature.noSignature)
    }
  }

  override def signMessage[MessageT <: ProtocolVersionedMemoizedEvidence with MessageFrom](
      message: MessageT,
      hashPurpose: HashPurpose,
  )(implicit
      traceContext: TraceContext
  ): SimulationFuture[Either[SyncCryptoError, SignedMessage[MessageT]]] = SimulationFuture { () =>
    Try {
      Right(SignedMessage(message, Signature.noSignature))
    }
  }

  override def verifySignature(hash: Hash, member: SequencerId, signature: Signature)(implicit
      traceContext: TraceContext
  ): SimulationFuture[Either[SignatureCheckError, Unit]] = SimulationFuture { () =>
    Try {
      Either.unit
    }
  }
}
