// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.EitherT
import cats.implicits.{catsSyntaxParallelTraverse_, toTraverseOps}
import com.digitalasset.canton.admin.api.client.commands.{
  ParticipantAdminCommands,
  TopologyAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.DarDescription
import com.digitalasset.canton.admin.api.client.data.topology.ListVettedPackagesResult
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.admin.grpc.{BaseQuery, TopologyStoreId}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.topology.transaction.{VettedPackage, VettedPackages}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.google.protobuf.ByteString
import io.grpc.Status
import monocle.Monocle.toAppliedFocusOps
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.HasParticipantId
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.util.{DarUtil, UploadablePackage}

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.Future

trait ParticipantAdminDarsConnection {
  this: ParticipantAdminConnection & HasParticipantId =>

  def uploadDarFiles(
      pkgs: Seq[UploadablePackage],
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    pkgs.parTraverse_(
      uploadDarFile(_, retryFor)
    )

  private def uploadDarFile(
      pkg: UploadablePackage,
      retryFor: RetryFor,
      vetTheDar: Boolean = false,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val file = ByteString.readFrom(pkg.inputStream())
    uploadDarLocally(
      pkg.resourcePath,
      file,
      pkg.packageId,
      retryFor,
      vetTheDar,
    )
  }

  def uploadDarFileWithVettingOnAllConnectedSynchronizers(
      path: Path,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      darFile <- Future {
        ByteString.readFrom(Files.newInputStream(path))
      }
      // we vet the dar ourselves to ensure we have retries around topology failures
      _ <- uploadDarLocally(
        path.toString,
        darFile,
        DarUtil.readPackageId(path.toString, Files.newInputStream(path)),
        retryFor,
        vetTheDar = false,
      )
      domains <- listConnectedDomains().map(_.map(_.synchronizerId))
      darResource = DarResource(path)
      _ <- domains.traverse { domainId =>
        vetDar(domainId, darResource, None)
      }
    } yield ()

  def vetDar(domainId: SynchronizerId, dar: DarResource, fromDate: Option[Instant])(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val cantonFromDate = fromDate.map(CantonTimestamp.assertFromInstant)
    ensureTopologyMapping[VettedPackages](
      // we publish to the authorized store so that it pushed on all the domains and the console commands are still useful when dealing with dars
      AuthorizedStore,
      s"dar ${dar.packageId} ${dar.metadata} is vetted in the authorized store",
      EitherT(
        getVettingState(None).map { vettedPackages =>
          val packages = vettedPackages.mapping.packages
          packages.find(_.packageId == dar.packageId) match {
            case Some(_) =>
              // we don't check the validFrom value, we assume that once it's part of the vetting state it can no longer be updated
              Right(vettedPackages)
            case None =>
              Left(vettedPackages)
          }
        }
      ),
      currentVettingState =>
        Right(
          updateVettingStateForDar(
            dar = dar,
            packageValidFrom = cantonFromDate,
            currentVetting = currentVettingState,
          )
        ),
      RetryFor.Automation,
    ).flatMap(_ =>
      retryProvider.waitUntil(
        RetryFor.Automation,
        s"vet_dar_on_domain",
        s"Dar ${dar.packageId} is vetted on domain $domainId",
        getVettingState(domainId).map(
          _.mapping.packages
            .find(_.packageId == dar.packageId)
            .fold(
              throw Status.NOT_FOUND
                .withDescription(
                  s"Dar ${dar.packageId} is not vetted on domain $domainId"
                )
                .asRuntimeException
            )(_ => ())
        ),
        logger,
      )
    )
  }

  def lookupDar(mainPackageId: String)(implicit
      traceContext: TraceContext
  ): Future[Option[ByteString]] =
    runCmd(
      ParticipantAdminConnection.LookupDarByteString(mainPackageId)
    )

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
                existingVettingState.validFrom
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
  )(implicit tc: TraceContext): Future[Seq[ListVettedPackagesResult]] = {
    listVettedPackages(participantId, Some(domainId))
  }

  def listVettedPackages(
      participantId: ParticipantId,
      domainId: Option[SynchronizerId],
  )(implicit tc: TraceContext): Future[Seq[ListVettedPackagesResult]] = {
    runCmd(
      TopologyAdminCommands.Read.ListVettedPackages(
        BaseQuery(
          store = domainId
            .map(TopologyStoreId.Synchronizer(_))
            .getOrElse(TopologyStoreId.Authorized),
          proposals = false,
          timeQuery = TimeQuery.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParticipant = participantId.filterString,
      )
    )
  }

  def getVettingState(
      domain: SynchronizerId
  )(implicit tc: TraceContext): Future[TopologyResult[VettedPackages]] = {
    getVettingState(Some(domain))
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def getVettingState(
      domain: Option[SynchronizerId]
  )(implicit tc: TraceContext): Future[TopologyResult[VettedPackages]] = {
    for {
      participantId <- getParticipantId()
      vettedState <- listVettedPackages(participantId, domain)
    } yield {
      vettedState
        .map(result =>
          TopologyResult(
            result.context,
            result.item,
          )
        ) match {
        case Seq() =>
          throw Status.NOT_FOUND
            .withDescription(s"No package vetting state found for domain $domain")
            .asRuntimeException
        case Seq(state) => state
        case other =>
          logger.warn(
            s"Vetted state contains multiple entries on domain $domain for $participantId: $other. Will use the last entry"
          )
          // TODO(#18175) - remove once canton can handle this and fixed the issue
          other.maxBy(_.base.serial)
      }
    }
  }

  def listDars(limit: PositiveInt = PositiveInt.MaxValue)(implicit
      traceContext: TraceContext
  ): Future[Seq[DarDescription]] =
    runCmd(
      ParticipantAdminCommands.Package.ListDars(filterName = "", limit)
    )

  private def uploadDarLocally(
      path: String,
      darFile: => ByteString,
      mainPackageId: String,
      retryFor: RetryFor,
      vetTheDar: Boolean,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    for {
      _ <- retryProvider
        .ensureThatO(
          retryFor,
          "upload_dar",
          s"DAR file $path with package id $mainPackageId has been uploaded.",
          // TODO(#5141) and TODO(#5755): consider if we still need a check here
          lookupDar(mainPackageId).map(_.map(_ => ())),
          runCmd(
            ParticipantAdminCommands.Package
              .UploadDar(
                path,
                vetAllPackages = vetTheDar,
                synchronizeVetting = vetTheDar,
                description = "",
                expectedMainPackageId = mainPackageId,
                requestHeaders = Map.empty,
                logger,
                Some(darFile),
              )
          ).map(_ => ()),
          logger,
        )
    } yield ()
  }

}
