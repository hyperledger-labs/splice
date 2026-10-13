// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.OptionT
import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands
import com.digitalasset.canton.admin.api.client.data.{
  ListConnectedSynchronizersResult,
  RegisteredSynchronizer,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnectionValidation
import com.digitalasset.canton.topology.{PhysicalSynchronizerId, SynchronizerId}
import com.github.blemale.scaffeine.Scaffeine
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.HasParticipantId

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait ParticipantAdminSynchronizerConnection {
  this: ParticipantAdminConnection & HasParticipantId =>

  private val synchronizerIdAliasCache =
    Scaffeine()
      .expireAfterWrite(1.minutes)
      .maximumSize(100)
      .buildAsync[SynchronizerAlias, SynchronizerId]()

  def listConnectedSynchronizers()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedSynchronizersResult]] = {
    runCmd(ParticipantAdminCommands.SynchronizerConnectivity.ListConnectedSynchronizers())
  }

  def getSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[SynchronizerId] = {
    synchronizerIdAliasCache.getFuture(
      synchronizerAlias,
      alias => getPhysicalSynchronizerId(alias).map(_.logical),
    )
  }

  def lookupPhysicalSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Option[PhysicalSynchronizerId]] =
    OptionT(for {
      configuredSynchronizers <- listRegisteredSynchronizers()
    } yield configuredSynchronizers.collectFirst {
      case (configuredSynchronizer, psid, _)
          if configuredSynchronizer.synchronizerAlias == synchronizerAlias && psid.isDefined =>
        psid.toOption
    }.flatten)
      .orElseF(
        runCmd(
          ParticipantAdminCommands.SynchronizerConnectivity.GetSynchronizerId(synchronizerAlias)
        ).map(Some(_)).recover {
          case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND => None
        }
      )
      .value

  private def listAllRegisteredSynchronizers()(implicit
      tc: TraceContext
  ): Future[Seq[RegisteredSynchronizer]] = {
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ListAllRegisteredSynchronizers
    )
  }

  def listRegisteredSynchronizers()(implicit
      tc: TraceContext
  ): Future[Seq[(SynchronizerConnectionConfig, data.ConfiguredPhysicalSynchronizerId, Boolean)]] = {
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ListActiveRegisteredSynchronizers
    ).map(_.map { config =>
      (
        config._1.toInternal,
        config._2,
        config._3,
      )
    })
  }

  def getPhysicalSynchronizerId(synchronizerId: SynchronizerId)(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] = listRegisteredSynchronizers().map(
    _.collectFirst {
      case (_, data.KnownPhysicalSynchronizerId(psid), _) if psid.logical == synchronizerId => psid
    }.getOrElse(
      throw Status.NOT_FOUND
        .withDescription(
          s"No synchronizer registered and handshaked for id $synchronizerId"
        )
        .asRuntimeException()
    )
  )
  def getPhysicalSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] = lookupPhysicalSynchronizerId(synchronizerAlias).map(
    _.getOrElse(
      throw Status.NOT_FOUND
        .withDescription(
          s"No synchronizer registered and handshaked for $synchronizerAlias"
        )
        .asRuntimeException()
    )
  )

  def reconnectAllSynchronizers()(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ReconnectSynchronizers(ignoreFailures =
        false
      )
    )
  }

  def disconnectFromAllSynchronizers()(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    synchronizers <- listConnectedSynchronizers()
    _ <- Future.sequence(
      synchronizers.map(synchronizer =>
        runCmd(
          ParticipantAdminCommands.SynchronizerConnectivity.DisconnectSynchronizer(
            synchronizer.synchronizerAlias
          )
        )
      )
    )
  } yield ()

  def connectSynchronizer(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    retryProvider.retryForClientCalls(
      "connect_synchronizer",
      s"participant is connected to $alias",
      runCmd(
        ParticipantAdminCommands.SynchronizerConnectivity
          .ReconnectSynchronizer(alias, retry = false)
      ).map(isConnected =>
        if (!isConnected) {
          val msg = s"failed to connect to $alias"
          throw Status.Code.FAILED_PRECONDITION.toStatus.withDescription(msg).asRuntimeException()
        }
      ),
      logger,
    )

  private def disconnectSynchronizer(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(ParticipantAdminCommands.SynchronizerConnectivity.DisconnectSynchronizer(alias))

  private implicit val prettyRegisteredSynchronizer: Pretty[RegisteredSynchronizer] =
    Pretty.adHocPrettyInstance

  def ensureSynchronizerRegisteredWithManualConnect(
      config: SynchronizerConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[RegisteredSynchronizer] = {
    require(
      config.manualConnect,
      "manualConnect must be true when trying to register only",
    )
    retryProvider
      .ensureThatO(
        retryFor,
        "synchronizer_registered_no_handshake",
        s"participant registered ${config.synchronizerAlias}",
        listSynchronizerConnectionConfig(config.synchronizerAlias).map(_.headOption),
        registerSynchronizer(config),
        logger,
      )
  }

  def ensureSynchronizerRegisteredAndConnected(
      config: SynchronizerConnectionConfig,
      overwriteExistingConnection: Boolean,
      reconnectOnSynchronizerConfigurationChange: Boolean,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = for {
    _ <- retryProvider
      .ensureThat(
        retryFor,
        "synchronizer_registered",
        s"participant registered ${config.synchronizerAlias} with config $config",
        lookupRegisteredSynchronizer(config.synchronizerAlias, config.psid).map {
          case Some(_) if !overwriteExistingConnection => Right(())
          // Canton rejects modifications of non-active connections and the connection will never become active again.
          // Not hard-crashing as the connect might still work and reconciliation automation will eventually fix the config.
          case Some(existing) if existing.status != data.RegisteredSynchronizer.Status.Active =>
            logger.warn(
              s"Connection for ${config.synchronizerAlias} with psid ${config.psid} is no longer active (status: ${existing.status}), skipping update of the connection config"
            )
            Right(())
          // We don't set the sequencer id when connecting but Canton returns it so we ignore it in the comparison here.
          case Some(existingConfig)
              if ParticipantAdminConnection.dropSequencerId(
                existingConfig.config.toInternal
              ) == ParticipantAdminConnection.dropSequencerId(config) =>
            Right(())
          case Some(other) => Left(Some(other.config.toInternal))
          case None => Left(None)
        },
        (existingConfig: Option[SynchronizerConnectionConfig]) =>
          existingConfig match {
            case None =>
              logger.info(s"Registering new synchronizer with config $config")
              registerSynchronizer(config)
            case Some(_) =>
              modifySynchronizerConnectionConfigAndReconnect(
                config.synchronizerAlias,
                config.psid,
                reconnectOnSynchronizerConfigurationChange,
                _ => Some(config),
              )
                .map(_ => ())
          },
        logger,
      )
    _ <- connectSynchronizer(config.synchronizerAlias)
  } yield ()

  private def registerSynchronizer(config: SynchronizerConnectionConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.RegisterSynchronizer(
        config,
        performHandshake = false,
        SequencerConnectionValidation.ThresholdActive,
        onboardingTransactions = Seq.empty,
      )
    )

  private def reconnectSynchronizer(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    _ <- retryProvider.retryForClientCalls(
      "reconnect_synchronizer_disconnect",
      s"participant is disconnected from $alias",
      disconnectSynchronizer(alias),
      logger,
    )
    _ <- connectSynchronizer(alias)
  } yield ()

  def isSynchronizerRegistered(
      synchronizerAlias: SynchronizerAlias
  )(implicit tc: TraceContext): Future[Boolean] =
    listSynchronizerConnectionConfig(synchronizerAlias).map(_.nonEmpty)

  private def listSynchronizerConnectionConfig(
      synchronizerAlias: SynchronizerAlias,
      psid: Option[PhysicalSynchronizerId] = None,
  )(implicit traceContext: TraceContext): Future[Seq[RegisteredSynchronizer]] =
    for {
      registeredSynchronizers <- listAllRegisteredSynchronizers()
    } yield {
      val withMatchingAlias = registeredSynchronizers.filter(
        _.config.synchronizerAlias == synchronizerAlias
      )
      def matchesPsid(
          registeredSynchronizer: RegisteredSynchronizer,
          filterPsid: PhysicalSynchronizerId,
      ) =
        registeredSynchronizer.psid.toOption.contains(filterPsid) ||
          registeredSynchronizer.config.synchronizerId.contains(filterPsid)
      val activeSynchronizers =
        withMatchingAlias.filter(
          _.status == data.RegisteredSynchronizer.Status.Active
        )
      psid match {
        case Some(filterPsid) =>
          val matchingPsid = withMatchingAlias.filter(matchesPsid(_, filterPsid))
          // If nothing matches the given psid, fall back to an active synchronizer with the alias.
          if (matchingPsid.nonEmpty) matchingPsid else activeSynchronizers
        case None =>
          activeSynchronizers
      }
    }

  def lookupRegisteredSynchronizer(
      synchronizerAlias: SynchronizerAlias,
      psid: Option[PhysicalSynchronizerId] = None,
  )(implicit tc: TraceContext): Future[Option[RegisteredSynchronizer]] =
    listSynchronizerConnectionConfig(synchronizerAlias, psid).map(_.headOption)

  def getRegisteredSynchronizer(
      synchronizerAlias: SynchronizerAlias,
      psid: Option[PhysicalSynchronizerId] = None,
  )(implicit traceContext: TraceContext): Future[RegisteredSynchronizer] =
    lookupRegisteredSynchronizer(synchronizerAlias, psid).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"Synchronizer $synchronizerAlias is not configured on the participant")
          .asRuntimeException()
      )
    )

  def modifySynchronizerConnectionConfig(
      synchronizer: SynchronizerAlias,
      psid: Option[PhysicalSynchronizerId],
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    retryProvider.retryForClientCalls(
      "modify_synchronizer_connection",
      "Set the new synchronizer connection if required",
      for {
        registeredSynchronizer <- getRegisteredSynchronizer(synchronizer, psid)
        newConfig = f(registeredSynchronizer.config.toInternal)
        configModified <- registeredSynchronizer.status match {
          case data.RegisteredSynchronizer.Status.Active =>
            newConfig match {
              case None =>
                logger.trace("No update to synchronizer connection config required")
                Future.successful(false)
              case Some(config) =>
                if (
                  registeredSynchronizer.psid.toOption
                    .exists(oldPsid => config.psid.exists(psid => psid != oldPsid))
                ) {
                  Future.failed(
                    Status.INVALID_ARGUMENT
                      .withDescription(
                        s"New config physical synchronizer id ${config.psid} cannot be different from the old one ${registeredSynchronizer.psid} for synchronizer $synchronizer"
                      )
                      .asRuntimeException()
                  )
                } else {
                  logger.info(
                    s"Updating to new synchronizer connection config for synchronizer $synchronizer. Old config: $registeredSynchronizer, new config: $config"
                  )
                  for {
                    _ <- setSynchronizerConnectionConfig(
                      config,
                      registeredSynchronizer.psid.toOption.orElse(config.psid),
                    )
                  } yield true
                }
            }
          case nonActiveStatus =>
            logger.info(
              s"Not modifying synchronizer connection config to $newConfig for $registeredSynchronizer as status is non active: $nonActiveStatus"
            )
            Future.successful(false)
        }
      } yield configModified,
      logger,
    )
  }

  private def modifyOrRegisterSynchronizerConnectionConfig(
      config: SynchronizerConnectionConfig,
      reconnectOnSynchronizerConfigurationChange: Boolean,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      isSynchronizerRegistered <- isSynchronizerRegistered(config.synchronizerAlias)
      needsReconnect <-
        if (isSynchronizerRegistered) {
          modifySynchronizerConnectionConfig(
            config.synchronizerAlias,
            config.psid,
            f,
          )
        } else {
          logger.info(s"Synchronizer ${config.synchronizerAlias} is new, registering")
          ensureSynchronizerRegisteredAndConnected(
            config,
            overwriteExistingConnection = true,
            reconnectOnSynchronizerConfigurationChange = reconnectOnSynchronizerConfigurationChange,
            retryFor = retryFor,
          ).map(_ => false)
        }
    } yield needsReconnect

  def modifySynchronizerConnectionConfigAndReconnect(
      synchronizerAlias: SynchronizerAlias,
      psid: Option[PhysicalSynchronizerId],
      reconnectOnSynchronizerConfigurationChange: Boolean,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      configModified <- modifySynchronizerConnectionConfig(synchronizerAlias, psid, f)
      _ <-
        if (configModified && reconnectOnSynchronizerConfigurationChange) {
          logger.info(
            s"reconnect to the synchronizer $synchronizerAlias for new sequencer configuration to take effect"
          )
          reconnectSynchronizer(synchronizerAlias)
        } else Future.unit
    } yield ()

  def modifyOrRegisterSynchronizerConnectionConfigAndReconnect(
      config: SynchronizerConnectionConfig,
      reconnectOnSynchronizerConfigurationChange: Boolean,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    require(config.psid.isDefined, "psid must be set")
    for {
      configModified <- modifyOrRegisterSynchronizerConnectionConfig(
        config,
        reconnectOnSynchronizerConfigurationChange,
        f,
        retryFor,
      )
      _ <-
        if (configModified && reconnectOnSynchronizerConfigurationChange) {
          logger.info(
            s"reconnect to the synchronizer ${config.synchronizerAlias} for new sequencer configuration to take effect"
          )
          reconnectSynchronizer(config.synchronizerAlias)
        } else Future.unit
    } yield ()
  }

  private def setSynchronizerConnectionConfig(
      config: SynchronizerConnectionConfig,
      psid: Option[PhysicalSynchronizerId],
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ModifySynchronizerConnection(
        psid,
        config,
        SequencerConnectionValidation.ThresholdActive,
      )
    )
}
