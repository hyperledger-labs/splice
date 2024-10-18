// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.{
  SpliceLedgerConnection,
  CommandPriority,
  ParticipantAdminConnection,
}
import io.circe.parser.decode
import org.lfdecentralizedtrust.splice.environment.RetryFor
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.AppConfiguration
import org.lfdecentralizedtrust.splice.util.UploadablePackage
import org.lfdecentralizedtrust.splice.validator.store.AppManagerStore
import org.lfdecentralizedtrust.splice.validator.util.{DarUtil, HttpUtil}
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import cats.implicits.*
import com.daml.ledger.javaapi.data.User
import org.lfdecentralizedtrust.splice.http.HttpClient
import io.grpc.Status

import java.io.{
  BufferedInputStream,
  BufferedReader,
  File,
  FileInputStream,
  InputStream,
  InputStreamReader,
}
import java.util.stream.Collectors
import scala.concurrent.{ExecutionContext, Future}

class AppManagerService(
    validatorParty: PartyId,
    ledgerConnection: SpliceLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    store: AppManagerStore,
)(implicit ec: ExecutionContext) {

  def registerApp(
      providerUserId: String,
      configuration: AppConfiguration,
      release: java.io.File,
      retryFor: RetryFor,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    if (configuration.version != 0) {
      throw new IllegalArgumentException(
        s"Configuration version on registration must be 0 but was ${configuration.version}"
      )
    }
    for {
      _ <- store
        .lookupLatestAppConfigurationByName(configuration.name)
        .flatMap(
          _.fold(Future.unit)(_ =>
            Future.failed(
              Status.ALREADY_EXISTS
                .withDescription(s"App with name ${configuration.name} already exists.")
                .asRuntimeException()
            )
          )
        )
      providerPartyId <- ledgerConnection.getOrAllocateParty(
        providerUserId,
        Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
        participantAdminConnection,
      )
      _ <- storeAppRelease(providerPartyId, release, retryFor, priority)
      _ <- store.storeAppConfiguration(
        AppManagerStore.AppConfiguration(
          providerPartyId,
          configuration,
        ),
        priority,
      )
      _ <- store.storeRegisteredApp(
        AppManagerStore.RegisteredApp(
          providerPartyId,
          configuration,
        ),
        priority,
      )
    } yield ()
  }

  def storeAppRelease(
      provider: PartyId,
      release: java.io.File,
      retryFor: RetryFor,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit
      tc: TraceContext
  ): Future[Unit] =
    for {
      releaseManifest <- Future {
        readAppRelease(release)
      }
      dars <- Future {
        readDars(new FileInputStream(release))
      }
      _ <- participantAdminConnection.uploadDarFiles(dars.map(_._1), retryFor)
      _ <- store.storeAppRelease(
        AppManagerStore.AppRelease(
          provider,
          definitions.AppRelease(
            releaseManifest.version,
            dars.map(_._2.toHexString).toVector,
          ),
        ),
        priority,
      )
    } yield ()

  def installApp(
      appUrl: AppManagerStore.AppUrl
  )(implicit
      tc: TraceContext,
      httpClient: HttpClient,
      mat: Materializer,
  ): Future[Unit] = {
    for {
      configuration <- HttpUtil
        .getHttpJson[definitions.GetAppConfigurationResult](
          appUrl.latestAppConfiguration
        )
        .map(_.configuration)
      releases <- configuration.releaseConfigurations.traverse { releaseConfig =>
        HttpUtil.getHttpJson[definitions.AppRelease](
          appUrl.appRelease(releaseConfig.releaseVersion)
        )
      }
      _ <- releases.traverse_(release =>
        store.storeAppRelease(AppManagerStore.AppRelease(appUrl.provider, release))
      )
      _ <- store.storeAppConfiguration(
        AppManagerStore.AppConfiguration(
          appUrl.provider,
          configuration,
        )
      )
      _ <- store.storeInstalledApp(
        AppManagerStore.InstalledApp(
          appUrl.provider,
          appUrl,
          configuration,
          Seq.empty,
        )
      )
    } yield ()
  }

  private def readAppRelease(file: File): definitions.AppReleaseUpload = {
    val archiveInputStream = readTarGz(file)
    val _ =
      LazyList
        .continually(archiveInputStream.getNextTarEntry())
        .takeWhile(_ != null)
        .find(x => x.getName.dropWhile(x => x != '/') == "/release.json")
        .getOrElse(throw new IllegalArgumentException("No release manifest in bundle"))
    val manifestString = new BufferedReader(new InputStreamReader(archiveInputStream))
      .lines()
      .collect(Collectors.joining("\n"));
    decode[definitions.AppReleaseUpload](manifestString).valueOr(err =>
      throw new IllegalArgumentException(s"Invalid release manifest: $err")
    )
  }

  private def readTarGz(file: File): TarArchiveInputStream =
    readTarGz(new FileInputStream(file))

  private def readTarGz(inputStream: InputStream): TarArchiveInputStream = {
    val uncompressedInputStream = new CompressorStreamFactory().createCompressorInputStream(
      new BufferedInputStream(inputStream)
    )
    new TarArchiveInputStream(new BufferedInputStream(uncompressedInputStream))
  }

  private def readDars(file: InputStream): Seq[(UploadablePackage, Hash, ByteString)] = {
    val archiveInputStream = readTarGz(file)
    LazyList
      .continually(archiveInputStream.getNextTarEntry())
      .takeWhile(_ != null)
      .filter(entry => entry.getName.dropWhile(x => x != '/').startsWith("/dars/") && entry.isFile)
      .map { entry =>
        DarUtil.readDar(new File(entry.getName).getName, archiveInputStream)
      }
  }

}
