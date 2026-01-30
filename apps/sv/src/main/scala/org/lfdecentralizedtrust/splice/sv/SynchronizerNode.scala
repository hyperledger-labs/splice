// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import com.digitalasset.canton.admin.api.client.data.SubmissionRequestAmplification
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.sv.config.{SvCometBftConfig, SvSequencerConfig}

import java.time.Duration

abstract class SynchronizerNode(
    val sequencerAdminConnection: SequencerAdminConnection,
    val mediatorAdminConnection: MediatorAdminConnection,
    val sequencerExternalPublicUrl: String,
    val sequencerAvailabilityDelay: Duration,
    val sequencerConfig: SequencerConfig,
    val mediatorSequencerAmplification: SubmissionRequestAmplification,
) {}

sealed trait SequencerConfig {}

object SequencerConfig {
  def fromConfig(
      sequencerConfig: SvSequencerConfig,
      cometbftConfig: Option[SvCometBftConfig],
  ): SequencerConfig = {
    if (sequencerConfig.isBftSequencer) {
      BftSequencerConfig()
    } else if (cometbftConfig.exists(_.enabled)) {
      CometBftSequencerConfig()
    } else {
      ReferenceSequenceConfig()
    }
  }
}

final case class BftSequencerConfig(
) extends SequencerConfig

final case class CometBftSequencerConfig(
) extends SequencerConfig

final case class ReferenceSequenceConfig(
) extends SequencerConfig
