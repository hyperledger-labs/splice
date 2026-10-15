// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator

import cats.syntax.either.*
import com.digitalasset.nonempty.NonEmpty
import com.digitalasset.canton.SequencerAlias
import com.digitalasset.canton.admin.api.client.data.{
  SequencerConnectionPoolDelays,
  SubmissionRequestAmplification,
  SubscriptionLivenessLimits,
}
import com.digitalasset.canton.config.{ClientConfig, CryptoConfig, CryptoProvider}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.protocol.StaticSynchronizerParameters
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.synchronizer.config.SynchronizerParametersConfig
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Status
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
  SynchronizerNode,
}
import org.lfdecentralizedtrust.splice.lsu.LsuSynchronizerNode
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorSynchronizerNodeConfig

/** Connections to a synchronizer node (sequencer + mediator) operated by this sync operator.
  *
  * Everything an upgrade needs beyond the sequencer's admin api is optional in config, and fails
  * loudly here if an upgrade is attempted without it.
  */
final class SyncOperatorSynchronizerNode(
    override val sequencerAdminConnection: SequencerAdminConnection,
    mediatorAdminConnectionO: Option[MediatorAdminConnection],
    config: SyncOperatorSynchronizerNodeConfig,
    logger: TracedLogger,
) extends SynchronizerNode(sequencerAdminConnection)
    with LsuSynchronizerNode
    with AutoCloseable {

  override def mediatorAdminConnection: MediatorAdminConnection =
    mediatorAdminConnectionO.getOrElse(
      throw SyncOperatorSynchronizerNode.missingUpgradeConfig("mediator.admin-api")
    )

  override def sequencerExternalPublicUrl: String =
    config.sequencer.externalPublicApiUrl.getOrElse(
      throw SyncOperatorSynchronizerNode.missingUpgradeConfig("sequencer.external-public-api-url")
    )

  override def internalSequencerConnection: GrpcSequencerConnection =
    config.sequencer.internalApi
      .map(SyncOperatorSynchronizerNode.toSequencerConnection)
      .getOrElse(throw SyncOperatorSynchronizerNode.missingUpgradeConfig("sequencer.internal-api"))

  override def mediatorSequencerAmplification: SubmissionRequestAmplification =
    config.mediator.fold(SubmissionRequestAmplification.NoAmplification)(
      _.sequencerRequestAmplification
    )

  override def mediatorSequencerConnectionPoolDelays: SequencerConnectionPoolDelays =
    config.mediator.fold(SequencerConnectionPoolDelays.default)(_.sequencerConnectionPoolDelays)

  override def mediatorSubscriptionLivenessLimits: SubscriptionLivenessLimits =
    config.mediator.fold(SubscriptionLivenessLimits.default)(_.subscriptionLivenessLimits)

  override def staticSynchronizerParameters(serial: NonNegativeInt): StaticSynchronizerParameters =
    SynchronizerParametersConfig(synchronizerLimits =
      config.synchronizerLimits
        .map(_.toInternal)
        .filter(_ => config.protocolVersion >= ProtocolVersion.v36)
    )
      .toStaticSynchronizerParameters(
        CryptoConfig(provider = CryptoProvider.Jce),
        config.protocolVersion,
        config.serial.getOrElse(serial),
      )
      .valueOr(err =>
        throw new IllegalArgumentException(s"Invalid synchronizer parameters config: $err")
      )
      .copy(topologyChangeDelay = config.topologyChangeDelayDuration.toInternal)

  override def close(): Unit = {
    val instances =
      Seq[AutoCloseable](sequencerAdminConnection) ++ mediatorAdminConnectionO.toList
    LifeCycle.close(instances*)(logger)
  }
}

object SyncOperatorSynchronizerNode {

  private def missingUpgradeConfig(field: String) =
    Status.FAILED_PRECONDITION
      .withDescription(
        s"synchronizer-nodes.<node>.$field is not configured, which a logical synchronizer " +
          "upgrade requires. Configure it on both the current and the successor node."
      )
      .asRuntimeException()

  private def toSequencerConnection(config: ClientConfig): GrpcSequencerConnection =
    new GrpcSequencerConnection(
      NonEmpty.mk(Set, Endpoint(config.address, config.port)),
      transportSecurity = config.tlsConfig.isDefined,
      customTrustCertificates = None,
      SequencerAlias.Default,
      sequencerId = None,
    )
}
