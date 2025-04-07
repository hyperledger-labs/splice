// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import io.grpc.Status
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.sv.config.{SvCometBftConfig, SvSequencerConfig}
import com.digitalasset.canton.sequencing.SubmissionRequestAmplification
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.driver.BftBlockOrdererConfig.P2PEndpointConfig
import org.apache.pekko.http.scaladsl.model.Uri

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
      cometbftConfig: Option[SvCometBftConfig],
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
    peerUrl: P2PEndpointConfig
) extends SequencerConfig {
  override def externalPeerUrl: Option[String] = Some(
    Uri(
      peerUrl.tlsConfig.filter(_.enabled).fold("http")(_ => "https"),
      Uri.Authority(
        Uri.Host(peerUrl.address),
        peerUrl.port.unwrap,
      ),
    ).toString()
  )
}

final case class CometBftSequencerConfig(
) extends SequencerConfig {
  override def externalPeerUrl: Option[String] = None
}

final case class ReferenceSequenceConfig(
) extends SequencerConfig {
  override def externalPeerUrl: Option[String] = None
}
