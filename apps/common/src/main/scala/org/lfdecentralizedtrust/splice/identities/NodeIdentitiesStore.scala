// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.identities

import org.lfdecentralizedtrust.splice.config.PeriodicBackupDumpConfig
import org.lfdecentralizedtrust.splice.environment.{BuildInfo, TopologyAdminConnection}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump.NodeKey
import org.lfdecentralizedtrust.splice.util.BackupDump
import com.digitalasset.canton.crypto.{EncryptionPublicKeyWithName, SigningPublicKeyWithName}
import com.digitalasset.canton.crypto.admin.grpc.PrivateKeyMetadata
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil

import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.Try

/** A store for accessing the node identities. */
class NodeIdentitiesStore(
    adminConnection: TopologyAdminConnection,
    backup: Option[(PeriodicBackupDumpConfig, Clock)],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def getNodeIdentitiesDump()(implicit
      tc: TraceContext
  ): Future[NodeIdentitiesDump] =
    for {
      id <- adminConnection.identity()
      keysMetadata <- adminConnection.listMyKeys()
      keys <- MonadUtil.sequentialTraverse(keysMetadata) {
        case PrivateKeyMetadata(publicKeyWithName, _, None) =>
          adminConnection
            .exportKeyPair(publicKeyWithName.publicKey.id)
            .map(keyBytes =>
              NodeIdentitiesDump.NodeKey.KeyPair(
                keyBytes.toByteArray.toSeq,
                publicKeyWithName.name.map(_.unwrap),
              ): NodeKey
            )
        case PrivateKeyMetadata(keyWithName, _, Some(kmsKeyId)) =>
          val keyTypeT: Try[NodeIdentitiesDump.NodeKey.KeyType] = Try(keyWithName match {
            case _: SigningPublicKeyWithName =>
              NodeIdentitiesDump.NodeKey.KeyType.Signing
            case _: EncryptionPublicKeyWithName =>
              NodeIdentitiesDump.NodeKey.KeyType.Encryption
            case _ =>
              throw new IllegalArgumentException(s"Unknown key type: $keyWithName")
          })
          Future.fromTry(keyTypeT).map { keyType =>
            NodeIdentitiesDump.NodeKey.KmsKeyId(
              keyType,
              kmsKeyId.unwrap,
              keyWithName.name.map(_.unwrap),
            ): NodeKey
          }
      }
      authorizedStoreSnapshot <- adminConnection.exportAuthorizedStoreSnapshot(id.uid)
    } yield {
      NodeIdentitiesDump(
        id,
        keys,
        authorizedStoreSnapshot,
        Some(BuildInfo.compiledVersion),
      )
    }

  /** Write a dump of the node identities to the configured backup location.
    *
    * Fails if no backup location is configured.
    *
    * @return: the name of the file to which the backup was written
    */
  def backupNodeIdentities()(implicit traceContext: TraceContext): Future[Path] =
    for {
      (dumpConfig, clock) <- Future {
        backup.getOrElse(
          throw new IllegalStateException(
            "cannot backup identities as no backup location is configured"
          )
        )
      }
      dump <- getNodeIdentitiesDump()
      path <- Future {
        blocking {
          // determine target file
          val now = clock.now.toInstant
          // Deliberately not using better files here
          // because it turns this into an absolute path which
          // then makes all the logging stuff below very confusing.
          val filename = NodeIdentitiesStore.dumpFilename(now)
          val fileDesc =
            s"node identities dump containing ${dump.keys.size} keys to ${dumpConfig.location.locationDescription} at path: $filename"
          logger.debug(s"Attempting to write $fileDesc")
          val path = BackupDump.write(
            dumpConfig.location,
            filename,
            dump.toJson.noSpaces,
            loggerFactory,
          )
          logger.info(s"Wrote $fileDesc")
          path
        }
      }
    } yield path
}

object NodeIdentitiesStore {
  def dumpFilename(now: Instant) =
    Paths.get(
      s"participant_identities_$now.json"
    )
}
