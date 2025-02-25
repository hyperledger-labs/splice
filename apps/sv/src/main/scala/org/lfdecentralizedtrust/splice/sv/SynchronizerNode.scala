// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import com.digitalasset.canton.networking.Endpoint
import io.grpc.Status
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.sv.config.{CometBftConfig, SvSequencerConfig}
import com.digitalasset.canton.sequencing.SubmissionRequestAmplification

import java.time.Duration

abstract class SynchronizerNode(
    val sequencerAdminConnection: SequencerAdminConnection,
    val mediatorAdminConnection: MediatorAdminConnection,
    val sequencerExternalPublicUrl: String,
    val sequencerAvailabilityDelay: Duration,
    val sequencerConfig: SequencerConfig,
    val mediatorSequencerAmplification: SubmissionRequestAmplification,
) {}

sealed trait SequencerConfig {
  def externalPeerUrl: Option[String]
}

object SequencerConfig {
  def fromConfig(
      sequencerConfig: SvSequencerConfig,
      cometbftConfig: Option[CometBftConfig],
  ): SequencerConfig = {
    if (sequencerConfig.isBftSequencer) {
      BftSequencerConfig(
        sequencerConfig.externalPeerApiUrlSuffix.getOrElse(
          throw Status.INVALID_ARGUMENT
            .withDescription("External peer URL is required")
            .asRuntimeException()
        )
      )
    } else if (cometbftConfig.exists(_.enabled)) {
      CometBftSequencerConfig()
    } else {
      ReferenceSequenceConfig()
    }
  }
}

final case class BftSequencerConfig(
    peerUrl: Endpoint
) extends SequencerConfig {
  override def externalPeerUrl: Option[String] = Some(peerUrl.toString)
}

final case class CometBftSequencerConfig(
) extends SequencerConfig {
  override def externalPeerUrl: Option[String] = None
}

final case class ReferenceSequenceConfig(
) extends SequencerConfig {
  override def externalPeerUrl: Option[String] = None
}
