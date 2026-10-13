// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.lsu

import com.digitalasset.canton.admin.api.client.data.{
  SequencerConnectionPoolDelays,
  SubmissionRequestAmplification,
  SubscriptionLivenessLimits,
}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.protocol.StaticSynchronizerParameters
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import org.lfdecentralizedtrust.splice.environment.MediatorAdminConnection

/** What [[LsuNodeInitializer]] needs from a synchronizer node to stand up its successor. */
trait LsuSynchronizerNode {

  def mediatorAdminConnection: MediatorAdminConnection

  def sequencerExternalPublicUrl: String

  def internalSequencerConnection: GrpcSequencerConnection

  def mediatorSequencerAmplification: SubmissionRequestAmplification

  def mediatorSequencerConnectionPoolDelays: SequencerConnectionPoolDelays

  def mediatorSubscriptionLivenessLimits: SubscriptionLivenessLimits

  def staticSynchronizerParameters(serial: NonNegativeInt): StaticSynchronizerParameters
}
