// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.EitherT
import com.digitalasset.canton.admin.api.client.commands.{
  ParticipantAdminCommands,
  TopologyAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.DarDescription
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.transaction.{VettedPackage, VettedPackages}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.google.protobuf.ByteString
import io.grpc.Status
import monocle.Monocle.toAppliedFocusOps
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.HasParticipantId
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  TopologyResult,
  TopologyTransactionType,
}
import org.lfdecentralizedtrust.splice.util.UploadablePackage

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.Future
import scala.util.Using

trait ParticipantAdminDarsConnection {
  this: ParticipantAdminConnection & HasParticipantId =>

  def uploadDarFiles(
      pkg: Seq[UploadablePackage],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    uploadDarsLocally(pkg, retryFor)
  }

  def uploadDarFileWithVettingOnAllConnectedSynchronizers(
      path: Path,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      darFile <- Future {
        Using.resource(Files.newInputStream(path)) { stream =>
          ByteString.readFrom(stream)
        }
      }
      // we vet the dar ourselves to ensure we have retries around topology failures
      _ <- uploadDarsLocally(
        Seq(UploadablePackage.fromByteString(path.getFileName.toString, darFile)),
        retryFor,
      )
      domains <- listConnectedDomains().map(_.map(_.synchronizerId))
      darResource = DarResource(path)
      _ <- MonadUtil.sequentialTraverse(domains) { domainId =>
        vetDars(domainId.logical, Seq(darResource), None, maxVettingDelay = None)
      }
    } yield ()

  def vetDars(
      synchronizerId: SynchronizerId,
      dars: Seq[DarResource],
      fromDate: Option[Instant],
      maxVettingDelay: Option[(Clock, NonNegativeFiniteDuration)],
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val cantonFromDate = fromDate.map(CantonTimestamp.assertFromInstant)

    retryProvider.retry(
      RetryFor.Automation,
      "dar_vetting",
      s"dars ${dars.map(_.packageId)} are vetted on $synchronizerId from $fromDate",
      lookupVettingState(
        Some(synchronizerId),
        TopologyAdminConnection.TopologyTransactionType.AuthorizedState,
      ).flatMap {
        case None =>
          for {
            participantId <- getParticipantId()
            _ <- ensureInitialMapping(
              Right(
                updateVettingStateForDars(
                  dars,
                  cantonFromDate,
                  VettedPackages.tryCreate(
                    participantId,
                    Seq.empty,
                  ),
                )
              )
            )
          } yield ()
        case Some(_) =>
          ensureTopologyMapping[VettedPackages](
            TopologyStoreId.Synchronizer(synchronizerId),
            s"dars ${dars.map(_.packageId)} are vetted on $synchronizerId from $fromDate",
            topologyTransactionType =>
              EitherT(
                getVettingState(synchronizerId, topologyTransactionType).map { vettedPackages =>
                  if (
                    dars
                      .forall(dar =>
                        vettedPackages.mapping.packages.exists(_.packageId == dar.packageId)
                      )
                  ) {
                    // we don't check the validFrom value, we assume that once it's part of the vetting state it can no longer be updated
                    Right(vettedPackages)
                  } else {
                    Left(vettedPackages)
                  }
                }
              ),
            currentVettingState =>
              Right(
                updateVettingStateForDars(
                  dars = dars,
                  packageValidFrom = cantonFromDate,
                  currentVetting = currentVettingState,
                )
              ),
            RetryFor.Automation,
            maxSubmissionDelay = maxVettingDelay,
          ).map(_ => ())
      },
      logger,
    )
  }

  def lookupDar(mainPackageId: String)(implicit
      traceContext: TraceContext
  ): Future[Option[ByteString]] =
    runCmd(
      ParticipantAdminConnection.LookupDarByteString(mainPackageId)
    )
  private def updateVettingStateForDars(
      dars: Seq[DarResource],
      packageValidFrom: Option[CantonTimestamp],
      currentVetting: VettedPackages,
  ) = {
    dars.foldLeft(currentVetting)((currentVetting, dar) =>
      updateVettingStateForDar(dar, packageValidFrom, currentVetting)
    )
  }

  private def updateVettingStateForDar(
      dar: DarResource,
      packageValidFrom: Option[CantonTimestamp],
      currentVetting: VettedPackages,
  ) = {
    currentVetting
      .focus(_.packages)
      .modify(packages => {
        def updateVettingStateForPackage(packageId: PackageId, packages: Seq[VettedPackage]) = {
          // while the main package is guaranteed to not exist the dependencies might already have been vetted, and they might have been vetted with a later date so we make sure that the dependencies are available as early as we need them
          packages.find(_.packageId == packageId) match {
            case Some(existingVettingState) =>
              if (
                existingVettingState.validFromInclusive
                  .exists(existingValidFrom =>
                    existingValidFrom.isAfter(CantonTimestamp.now()) &&
                      packageValidFrom.forall(_ isBefore existingValidFrom)
                  )
              ) {
                packages.filterNot(_.packageId == packageId) :+ VettedPackage(
                  packageId,
                  packageValidFrom,
                  None,
                )
              } else {
                packages
              }
            case None => packages :+ VettedPackage(packageId, packageValidFrom, None)
          }
        }
        (dar.dependencyPackageIds.toSeq :+ dar.packageId)
          .map(
            PackageId.assertFromString
          )
          .foldLeft(packages) { case (vettingState, newPackage) =>
            updateVettingStateForPackage(newPackage, vettingState)
          }
      })
  }

  def listVettedPackages(
      participantId: ParticipantId,
      domainId: SynchronizerId,
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[VettedPackages]]] = {
    listVettedPackages(participantId, Some(domainId), topologyTransactionType)
  }

  def listVettedPackages(
      participantId: ParticipantId,
      domainId: Option[SynchronizerId],
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[Seq[TopologyResult[VettedPackages]]] = {
    runCommand(
      domainId
        .map(TopologyStoreId.Synchronizer(_))
        .getOrElse(TopologyStoreId.Authorized),
      topologyTransactionType,
    )(
      TopologyAdminCommands.Read.ListVettedPackages(
        _,
        filterParticipant = participantId.filterString,
      )
    )
  }

  def getVettingState(
      domain: SynchronizerId,
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[TopologyResult[VettedPackages]] = {
    getVettingState(Some(domain), topologyTransactionType)
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def getVettingState(
      domain: Option[SynchronizerId],
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[TopologyResult[VettedPackages]] =
    lookupVettingState(domain, topologyTransactionType).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No package vetting state found for domain $domain")
          .asRuntimeException
      )
    )

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def lookupVettingState(
      domain: Option[SynchronizerId],
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[Option[TopologyResult[VettedPackages]]] = {
    for {
      participantId <- getParticipantId()
      vettedState <- listVettedPackages(participantId, domain, topologyTransactionType)
    } yield {
      vettedState match {
        case Seq() => None
        case Seq(state) => Some(state)
        case other =>
          logger.warn(
            s"Vetted state contains multiple entries on domain $domain for $participantId: $other. Will use the last entry"
          )
          // TODO(DACH-NY/canton-network-node#18175) - remove once canton can handle this and fixed the issue
          Some(other.maxBy(_.base.serial))
      }
    }
  }

  def listDars(limit: PositiveInt = PositiveInt.MaxValue)(implicit
      traceContext: TraceContext
  ): Future[Seq[DarDescription]] =
    runCmd(
      ParticipantAdminCommands.Package.ListDars(filterName = "", limit)
    )

  private def uploadDarsLocally(
      dars: Seq[UploadablePackage],
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      existingDars <- listDars().map(_.map(_.mainPackageId))
      darsToUploads = dars.filterNot(dar => existingDars.contains(dar.packageId))
      _ <- MonadUtil.parTraverseWithLimit(PositiveInt.tryCreate(5))(darsToUploads)(
        uploadDar(_, retryFor)
      )
    } yield {}
  }

  private def uploadDar(dar: UploadablePackage, retryFor: RetryFor)(implicit
      tc: TraceContext
  ) = {
    retryProvider.retry(
      retryFor,
      "upload_dar",
      s"Upload dar ${dar.packageId} (without vetting)",
      runCmd(
        ParticipantAdminCommands.Package
          .UploadDar(
            darPath = dar.resourcePath,
            synchronizerId = None,
            vetAllPackages = false,
            synchronizeVetting = false,
            description = "",
            expectedMainPackageId = dar.packageId,
            requestHeaders = Map.empty,
            logger,
            Some(
              Using.resource(
                dar.inputStream()
              ) { stream =>
                ByteString.readFrom(stream)
              }
            ),
          )
      ).map(_ => ()),
      logger,
    )
  }
}
