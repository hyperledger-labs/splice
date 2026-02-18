// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.daml.ledger.api.v2 as lapi
import com.daml.ledger.api.v2.admin.identity_provider_config_service.IdentityProviderConfig
import com.daml.ledger.api.v2.admin.{ObjectMetaOuterClass, UserManagementServiceOuterClass}
import com.daml.ledger.api.v2.event
import com.daml.ledger.api.v2.package_reference.PackageReference
import com.daml.ledger.javaapi.data.codegen.{ContractId, Created, Exercised, HasCommands, Update}
import com.daml.ledger.javaapi.data.{Command, CreatedEvent, ExercisedEvent, Transaction, User}
import com.digitalasset.base.error.ErrorResource
import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.base.error.utils.ErrorDetails.ResourceInfoDetail
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.data.parties.PartyDetails
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.error.LedgerApiErrors
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  FutureUnlessShutdown,
  SyncCloseable,
}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.LocalRejectError.ConsistencyRejections.InactiveContracts
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.{Namespace, PartyId, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.{LoggerUtil, PekkoUtil}
import com.digitalasset.daml.lf.data.Ref
import com.google.protobuf.field_mask.FieldMask
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.CircuitBreakerOpenException
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.stream.{KillSwitch, KillSwitches, Materializer}
import org.apache.pekko.{Done, NotUsed}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  DedupConfig,
  DedupOffset,
  IncompleteReassignmentEvent,
  LedgerClient,
  NoDedup,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionFilter
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  DisclosedContracts,
  SpliceCircuitBreaker,
}
import shapeless.<:!<

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success, Try}

/** BaseLedgerConnection is a read-only ledger connection, typically used during initialization when we don't
  * want to allow command submissions yet.
  */
class BaseLedgerConnection(
    client: LedgerClient,
    userId: String,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContextExecutor)
    extends NamedLogging {

  logger.debug(s"Created connection with userId=$userId")(
    TraceContext.empty
  )

  import BaseLedgerConnection.*

  def deleteUser(
      userId: String,
      identityProviderId: Option[String] = None,
  )(implicit tc: TraceContext): Future[Unit] =
    client.deleteUser(userId, identityProviderId)

  def ledgerEnd()(implicit
      traceContext: TraceContext
  ): Future[Long] =
    client.ledgerEnd()

  def latestPrunedOffset()(implicit
      traceContext: TraceContext
  ): Future[Long] =
    client.latestPrunedOffset()

  def activeContracts(
      eventFormat: com.daml.ledger.api.v2.transaction_filter.EventFormat,
      offset: Long,
  )(implicit tc: TraceContext): Source[BaseLedgerConnection.ActiveContractsItem, NotUsed] = {
    val activeContractsRequest = client.activeContracts(
      lapi.state_service.GetActiveContractsRequest(
        activeAtOffset = offset,
        eventFormat = Some(eventFormat),
      )
    )
    activeContractsRequest
      .map(_.contractEntry)
      .map[Option[BaseLedgerConnection.ActiveContractsItem]] {
        case lapi.state_service.GetActiveContractsResponse.ContractEntry.Empty =>
          None
        case lapi.state_service.GetActiveContractsResponse.ContractEntry.ActiveContract(contract) =>
          Some(
            BaseLedgerConnection.ActiveContractsItem
              .ActiveContract(
                org.lfdecentralizedtrust.splice.environment.ledger.api.ActiveContract
                  .fromProto(contract)
              )
          )
        case lapi.state_service.GetActiveContractsResponse.ContractEntry.IncompleteUnassigned(ev) =>
          Some(
            BaseLedgerConnection.ActiveContractsItem
              .IncompleteUnassign(IncompleteReassignmentEvent.fromProto(ev))
          )
        case lapi.state_service.GetActiveContractsResponse.ContractEntry.IncompleteAssigned(ev) =>
          Some(
            BaseLedgerConnection.ActiveContractsItem
              .IncompleteAssign(IncompleteReassignmentEvent.fromProto(ev))
          )
      }
      .collect { case Some(item) =>
        item
      }
  }

  def activeContracts(
      filter: IngestionFilter,
      offset: Long,
  )(implicit tc: TraceContext): Source[BaseLedgerConnection.ActiveContractsItem, NotUsed] =
    activeContracts(filter.toEventFormat, offset)

  def getContract(
      contractId: ContractId[?],
      queryingParties: Seq[PartyId],
  )(implicit tc: TraceContext): Future[Option[event.CreatedEvent]] = {
    client.getContract(contractId, queryingParties)
  }

  def getConnectedDomains(party: PartyId)(implicit
      tc: TraceContext
  ): Future[Map[SynchronizerAlias, SynchronizerId]] =
    client.getConnectedDomains(party)

  def updates(
      beginOffset: Long,
      filter: IngestionFilter,
  )(implicit tc: TraceContext): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] =
    client
      .updates(LedgerClient.GetUpdatesRequest(beginOffset, None, filter))

  def getOptionalPrimaryParty(
      user: String,
      identityProviderId: Option[String] = None,
  )(implicit tc: TraceContext): Future[Option[PartyId]] = {
    for {
      user <- client
        .getUser(user, identityProviderId)
        .map(Some(_))
      partyId = user.flatMap(_.getPrimaryParty.toScala).map(PartyId.tryFromProtoPrimitive)
    } yield partyId
  }

  def getPrimaryParty(user: String)(implicit tc: TraceContext): Future[PartyId] = {
    for {
      partyIdO <- getOptionalPrimaryParty(user)
      partyId = partyIdO.getOrElse(
        throw Status.FAILED_PRECONDITION
          .withDescription(s"User $user has no primary party")
          .asRuntimeException
      )
    } yield partyId
  }

  def ensureUserPrimaryPartyIsAllocated(
      userId: String,
      hint: String,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] =
    retryProvider.ensureThatO(
      RetryFor.WaitingOnInitDependency,
      "primary_party_allocated",
      s"User $userId has primary party",
      check = getOptionalPrimaryParty(userId),
      establish = for {
        party <- ensurePartyAllocated(
          TopologyStoreId.Authorized,
          hint,
          None,
          participantAdminConnection,
        )
        _ <- setUserPrimaryParty(userId, party)
        _ <- grantUserRights(userId, actAsParties = Seq(party), readAsParties = Seq.empty)
      } yield (),
      logger,
    )

  def ensureUserHasPrimaryParty(
      userId: String,
      partyId: PartyId,
  )(implicit traceContext: TraceContext): Future[PartyId] = {
    for {
      partyId <- retryProvider.ensureThatO(
        RetryFor.WaitingOnInitDependency,
        "primary_party_set",
        s"User $userId has primary party",
        check = getOptionalPrimaryParty(userId),
        establish = setUserPrimaryParty(userId, partyId),
        logger,
      )
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "act_as_rights_granted",
        s"User $userId has actAs rights for $partyId",
        check = getUserActAs(userId).map(_.contains(partyId)),
        establish = grantUserRights(userId, actAsParties = Seq(partyId), readAsParties = Seq.empty),
        logger,
      )
    } yield partyId
  }

  def ensurePartyAllocated(
      store: TopologyStoreId,
      hint: String,
      namespaceO: Option[Namespace],
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext) =
    for {
      participantId <- participantAdminConnection.getParticipantId()
      namespace = namespaceO.getOrElse(participantId.uid.namespace)
      partyId = PartyId(
        UniqueIdentifier.tryCreate(
          hint,
          namespace,
        )
      )
      _ <- participantAdminConnection
        .ensureInitialPartyToParticipant(
          store,
          partyId,
          participantId,
        )
      _ <- waitForPartyOnLedgerApi(partyId)
    } yield partyId

  def waitForPartyOnLedgerApi(
      party: PartyId
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "ledger_api_wait_party",
      show"Party $party is observed on ledger API",
      getParty(party).map { details =>
        if (details.isEmpty)
          throw Status.NOT_FOUND
            .withDescription(s"Party allocation of $party not observed on ledger API")
            .asRuntimeException()
      },
      logger,
    )

  def getPartyByHint(hint: String, participantAdminConnection: ParticipantAdminConnection)(implicit
      traceContext: TraceContext
  ): Future[Option[PartyDetails]] = {
    for {
      participantId <- participantAdminConnection.getParticipantId()
      partyId = PartyId(
        UniqueIdentifier.tryCreate(
          hint,
          participantId.uid.namespace,
        )
      )
      party <- getParty(partyId)
    } yield party
  }

  def getParty(
      party: PartyId
  )(implicit traceContext: TraceContext): Future[Option[PartyDetails]] = {
    client.getParties(Seq(party)).map(_.headOption)
  }

  def getUser(user: String, identityProviderId: Option[String] = None)(implicit
      tc: TraceContext
  ): Future[User] =
    client.getUser(user, identityProviderId)

  private def createPartyAndUser(
      user: String,
      userRights: Seq[User.Right],
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] =
    for {
      party <- ensurePartyAllocated(
        TopologyStoreId.Authorized,
        sanitizeUserIdToPartyString(user),
        None,
        participantAdminConnection,
      )
      _ <- createUserWithPrimaryParty(user, party, userRights)
    } yield party

  def createUserWithPrimaryParty(
      user: String,
      party: PartyId,
      userRights: Seq[User.Right],
      identityProviderId: Option[String] = None,
  )(implicit tc: TraceContext): Future[PartyId] = {
    val userId = Ref.UserId.assertFromString(user)
    val userLf = new User(userId, party.toLf)
    for {
      user <- client
        .getOrCreateUser(
          userLf,
          new User.Right.CanActAs(party.toLf) +: userRights,
          false,
          Map.empty,
          identityProviderId,
        )
      partyId =
        PartyId.tryFromProtoPrimitive(
          user.getPrimaryParty.toScala
            .getOrElse(sys.error(s"user $user was allocated without primary party"))
        )
    } yield partyId
  }

  // Doesn't update an existing user; only creates a new one if a config with that ID doesn't exist.
  def ensureUserCreated(
      user: String,
      primaryParty: Option[PartyId],
      rights: Seq[User.Right],
      isDeactivated: Boolean = false,
      annotations: Map[String, String] = Map.empty,
      identityProviderId: Option[String] = None,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val userId = Ref.UserId.assertFromString(user)
    val userLf = primaryParty match {
      case None =>
        new User(userId)
      case Some(party) =>
        new User(userId, party.toLf)
    }
    client
      .getOrCreateUser(userLf, rights, isDeactivated, annotations, identityProviderId)
      .map(_ => ())
  }

  def setUserPrimaryParty(
      user: String,
      party: PartyId,
      identityProviderId: Option[String] = None,
  )(implicit tc: TraceContext): Future[Unit] =
    client.setUserPrimaryParty(user, party, identityProviderId)

  def ensureUserMetadataAnnotation(userId: String, key: String, value: String, retryFor: RetryFor)(
      implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] =
    ensureUserMetadataAnnotation(userId, Map(key -> value), retryFor)

  def ensureUserMetadataAnnotation(
      userId: String,
      annotations: Map[String, String],
      retryFor: RetryFor,
      identityProviderId: Option[String] = None,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Unit] =
    retryProvider.ensureThatB(
      retryFor,
      "ensure_user_annotation",
      s"user $userId has metadata annotations $annotations",
      client.getUserProto(userId, identityProviderId).map { user =>
        if (!user.hasMetadata)
          false
        else {
          val existingAnnotations = user.getMetadata.getAnnotationsMap.asScala
          annotations.forall { case (k, v) => existingAnnotations.get(k).fold(false)(_ == v) }
        }
      },
      for {
        user <- client.getUserProto(userId, identityProviderId)
        newMetadata =
          if (user.hasMetadata) {
            val metadata = user.getMetadata
            metadata.toBuilder.putAllAnnotations(annotations.asJava).build
          } else {
            ObjectMetaOuterClass.ObjectMeta.newBuilder.putAllAnnotations(annotations.asJava).build
          }
        newUser = user.toBuilder.setMetadata(newMetadata).build
        mask = FieldMask(Seq("metadata.annotations", "metadata.resource_version"))
        _ <- client.updateUser(newUser, mask)
      } yield (),
      logger,
    )

  def waitForUserMetadata(
      userId: String,
      key: String,
      identityProviderId: Option[String] = None,
  )(implicit traceContext: TraceContext): Future[String] =
    retryProvider.getValueWithRetriesNoPretty(
      RetryFor.WaitingOnInitDependency,
      "wait_user_metadata",
      s"metadata field $key of user $userId",
      client.getUserProto(userId, identityProviderId).map { user =>
        if (user.hasMetadata) {
          val metadata = user.getMetadata
          metadata.getAnnotationsMap.asScala.getOrElse(
            key,
            throw Status.NOT_FOUND
              .withDescription(s"User $userId has no metadata annotation for key $key")
              .asRuntimeException(),
          )
        } else {
          throw Status.NOT_FOUND
            .withDescription(s"User $userId has no metadata")
            .asRuntimeException()
        }
      },
      logger,
    )

  def lookupUserMetadata(
      userId: String,
      key: String,
      identityProviderId: Option[String] = None,
  )(implicit tc: TraceContext): Future[Option[String]] =
    client.getUserProto(userId, identityProviderId).map { user =>
      if (user.hasMetadata) {
        user.getMetadata.getAnnotationsMap.asScala.get(key)
      } else {
        None
      }
    }

  // TODO(tech-debt): Factor out user/party allocation and make it robust (current implementation is racy)
  def getOrAllocateParty(
      username: String,
      userRights: Seq[User.Right] = Seq.empty,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] = {
    for {
      existingPartyId <- getOptionalPrimaryParty(username).recover {
        case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
          None
      }
      partyId <- existingPartyId.fold[Future[PartyId]](
        createPartyAndUser(username, userRights, participantAdminConnection)
      )(
        Future.successful
      )
    } yield partyId
  }

  def getUserActAs(
      username: String
  )(implicit tc: TraceContext): Future[Set[PartyId]] = {
    val userId = Ref.UserId.assertFromString(username)
    for {
      userRights <- client.listUserRights(userId)
    } yield userRights.collect { case actAs: User.Right.CanActAs =>
      PartyId.tryFromProtoPrimitive(actAs.party)
    }.toSet
  }

  def listUserRights(
      username: String
  )(implicit tc: TraceContext): Future[Set[User.Right]] = {
    val userId = Ref.UserId.assertFromString(username)
    for {
      userRights <- client.listUserRights(userId)
    } yield userRights.toSet
  }

  def grantUserRights(
      user: String,
      actAsParties: Seq[PartyId],
      readAsParties: Seq[PartyId],
  )(implicit tc: TraceContext): Future[Unit] = {
    val grants =
      actAsParties.map(p => new User.Right.CanActAs(p.toLf)) ++ readAsParties.map(p =>
        new User.Right.CanReadAs(p.toLf)
      )
    client.grantUserRights(user, grants)
  }

  def revokeUserRights(
      user: String,
      actAsParties: Seq[PartyId],
      readAsParties: Seq[PartyId],
  )(implicit tc: TraceContext): Future[Unit] = {
    val revokes =
      actAsParties.map(p => new User.Right.CanActAs(p.toLf)) ++ readAsParties.map(p =>
        new User.Right.CanReadAs(p.toLf)
      )
    client.revokeUserRights(user, revokes)
  }

  def listPackages()(implicit tc: TraceContext): Future[Set[String]] =
    client.listPackages().map(_.toSet)

  def waitForPackages(
      requiredPackageIds: Set[String]
  )(implicit traceContext: TraceContext): Future[Unit] = {

    if (requiredPackageIds.isEmpty) {
      logger.debug("Skipping waiting for required packages to be uploaded, as there are none.")
      Future.unit
    } else
      retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "wait_packages",
        show"packages for $requiredPackageIds are uploaded",
        for {
          actual <- (listPackages(): Future[Set[String]])
        } yield {
          val missing = requiredPackageIds.filter(pkgId => !actual.contains(pkgId))
          if (missing.isEmpty) {
            ()
          } else {
            throw Status.NOT_FOUND.withDescription(show"packages for $missing").asRuntimeException()
          }
        },
        logger,
      )
  }

  // Note that this will only work for apps that run as the SV user, i.e., the sv app, directory and scan.
  def getDsoPartyFromUserMetadata(userId: String)(implicit
      traceContext: TraceContext
  ): Future[PartyId] =
    waitForUserMetadata(userId, DSO_PARTY_USER_METADATA_KEY).map(
      PartyId.tryFromProtoPrimitive(_)
    )

  def getInitialRoundFromUserMetadata(userId: String)(implicit
      traceContext: TraceContext
  ): Future[String] =
    waitForUserMetadata(
      userId,
      INITIAL_ROUND_USER_METADATA_KEY,
    )

  // Note that this will only work for apps that run as the SV user, i.e., the sv app, directory and scan.
  def lookupDsoPartyFromUserMetadata(userId: String)(implicit
      tc: TraceContext
  ): Future[Option[PartyId]] =
    lookupUserMetadata(userId, DSO_PARTY_USER_METADATA_KEY).map(
      _.map(PartyId.tryFromProtoPrimitive(_))
    )

  def getSvNameFromUserMetadata(userId: String)(implicit
      traceContext: TraceContext
  ): Future[String] =
    waitForUserMetadata(userId, SV_NAME_USER_METADATA_KEY)

  def listAllUserData()(implicit
      tc: TraceContext
  ): Future[Seq[(UserManagementServiceOuterClass.User, Seq[User.Right])]] =
    for {
      identityProviderConfigs <- listIdentityProviderConfigs()
      // the `Seq(None)` is so we also get the users that have no explicit idp set
      identityProviderIds: Seq[Option[String]] = Seq(None) ++ identityProviderConfigs.map(
        (c: IdentityProviderConfig) => Some(c.identityProviderId)
      )
      allUsers <- MonadUtil
        .sequentialTraverse(identityProviderIds)(listAllUsersProto(None, 1000, _))
        .map(_.flatten)
      allUserData <- MonadUtil.sequentialTraverse(allUsers) { user =>
        for {
          rights <- client.listUserRights(
            user.getId,
            Option(user.getIdentityProviderId).filter(_.nonEmpty),
          )
        } yield (user, rights)
      }
    } yield allUserData

  private def listAllUsersProto(
      pageToken: Option[String],
      pageSize: Int,
      identityProviderId: Option[String],
  )(implicit tc: TraceContext): Future[Seq[UserManagementServiceOuterClass.User]] =
    client.listUsersProto(pageToken, pageSize, identityProviderId).flatMap {
      case (users, nextToken) =>
        if (nextToken.isEmpty) {
          Future.successful(users)
        } else {
          listAllUsersProto(nextToken, pageSize, identityProviderId).map(users ++ _)
        }
    }

  def listIdentityProviderConfigs()(implicit
      tc: TraceContext
  ): Future[Seq[IdentityProviderConfig]] =
    client.listIdentityProviderConfigs()

  // Doesn't update an existing config; only creates a new one if a config with that ID doesn't exist.
  def ensureIdentityProviderConfigCreated(
      id: String,
      isDeactivated: Boolean,
      jwksUrl: String,
      issuer: String,
      audience: String,
  )(implicit
      tc: TraceContext
  ): Future[Unit] =
    retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
      "ensure_identity_provider_config_created",
      show"Create identity provider $id",
      client.createIdentityProviderConfig(id, isDeactivated, jwksUrl, issuer, audience).recover {
        case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.ALREADY_EXISTS =>
          logger.info(show"Identity provider $id already exists")
      },
      logger,
    )

  def getSupportedPackageVersion(
      synchronizerId: SynchronizerId,
      packageRequirements: Seq[(String, Seq[PartyId])],
      vettingAsOfTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Seq[PackageReference]] = {
    retryProvider.retryForClientCalls(
      "get_supported_package_version",
      s"Get the supported package version for packageRequirements $packageRequirements on synchronizer $synchronizerId with vetting time ${vettingAsOfTime}",
      client
        .getSupportedPackageVersion(
          synchronizerId,
          packageRequirements,
          vettingAsOfTime,
        )
        .recover {
          case ex: StatusRuntimeException
              if ErrorDetails.matches(ex, LedgerApiErrors.NoPreferredPackagesFound) =>
            logger.info(
              s"No preferred packages found for packageRequirements $packageRequirements on synchronizer $synchronizerId with vetting time $vettingAsOfTime"
            )
            Seq.empty
        },
      logger,
    )
  }
}

/** Subscription for reading the ledger */
class SpliceLedgerSubscription[S](
    source: Source[S, NotUsed],
    map: S => Future[Unit],
    override protected[this] val retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends RetryProvider.Has
    with FlagCloseableAsync
    with NamedLogging {

  import TraceContext.Implicits.Empty.*

  case object SubscriptionShutDown
  private val lastFutureFinished
      : AtomicReference[Either[SubscriptionShutDown.type, Promise[Done]]] = new AtomicReference(
    Right(Promise.successful(Done))
  )

  private val (killSwitch, completed_) = PekkoUtil.runSupervised(
    source
      // we place the kill switch before the map operator, such that
      // we can shut down the operator quickly and signal upstream to cancel further sending
      .viaMat(KillSwitches.single)(Keep.right)
      .viaMat(Flow[S].mapAsync(1) { el =>
        // map(el) *immediately* launches the future, so it needs to be done after setting the promise,
        // and only if we're not shutting down.
        val myPromise = Promise[Done]()
        val previousState = lastFutureFinished.getAndSet(Right(myPromise))
        previousState match {
          case Left(SubscriptionShutDown) =>
            Future.successful(Done)
          case Right(_) =>
            map(el).andThen { case _ =>
              myPromise.success(Done)
            }
        }
      })(Keep.left)
      // and we get the Future[Done] as completed from the sink so we know when the last message
      // was processed... except when a Failure from the source happens (e.g., `STALE_STREAM_AUTHORIZATION`),
      // in which case the stream will be reported as completed with a failure, while the Future is running.
      // Therefore, we also keep track of the last running future and include that in the completed check.
      // If we didn't, we'd get situations where e.g. two ingestions are running simultaneously (and break a lot).
      // For more information, see https://github.com/DACH-NY/canton-network-node/issues/10126.
      .toMat(Sink.ignore)(Keep.both),
    errorLogMessagePrefix = if (retryProvider.isClosing) {
      "Ignoring failure to handle transaction, as we are shutting down"
    } else {
      "Fatally failed to handle transaction"
    },
  )

  def completed: Future[Done] =
    completed_.transformWith { result =>
      (lastFutureFinished.getAndSet(Left(SubscriptionShutDown)) match {
        case Left(_) => Future.successful(Done)
        case Right(runningFuture) => runningFuture.future
      }).transformWith(_ =>
        Future.fromTry(result)
      ) // Keep whatever the original reason for failure was
    }

  def isActive: Boolean = !completed_.isCompleted

  def initiateShutdown() =
    killSwitch.shutdown()

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    import TraceContext.Implicits.Empty.*
    List[AsyncOrSyncCloseable](
      SyncCloseable(s"terminating ledger api stream", killSwitch.shutdown()),
      AsyncCloseable(
        s"ledger api stream terminated",
        completed.transform {
          case Success(v) => Success(v)
          case Failure(_: StatusRuntimeException) =>
            // don't fail to close if there was a grpc status runtime exception
            // this can happen (i.e. server not available etc.)
            Success(Done)
          case Failure(ex) => Failure(ex)
        },
        timeouts.shutdownShort,
      ),
    )
  }
}

/** A ledger connection with full submission functionality */
class SpliceLedgerConnection(
    client: LedgerClient,
    userId: String,
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    inactiveContractCallbacks: AtomicReference[Seq[String => Unit]],
    contractDowngradeErrorCallbacks: AtomicReference[Seq[() => Unit]],
    trafficBalanceServiceO: AtomicReference[Option[TrafficBalanceService]],
    completionOffsetCallback: Long => Future[Unit],
    commandCircuitBreaker: SpliceCircuitBreaker,
)(implicit as: ActorSystem, ec: ExecutionContextExecutor)
    extends BaseLedgerConnection(
      client,
      userId,
      loggerFactory,
      retryProvider,
    ) {

  import SpliceLedgerConnection.*

  private[this] def timeouts = retryProvider.timeouts

  def disclosedContracts(
      arg: ContractWithState[?, ?],
      args: ContractWithState[?, ?]*
  ): DisclosedContracts.NE = {
    DisclosedContracts(inactiveContractCallbacks.get(), arg, args*)
  }

  def trafficBalanceService: Option[TrafficBalanceService] = trafficBalanceServiceO.get()

  private def callCallbacksOnCompletion[T, U](
      result: Future[T]
  )(getOffsetAndResult: T => (Option[Long], U)): Future[U] = {
    import TraceContext.Implicits.Empty.*
    def callCallbacksInternal(result: Try[T]): Unit =
      LoggerUtil.logOnThrow { // in case the callbacks throw.
        result match {
          case Success(_) => ()
          case Failure(ex: io.grpc.StatusRuntimeException)
              if ex.getMessage.contains(InactiveContracts.id) =>
            val statusProto = io.grpc.protobuf.StatusProto.fromThrowable(ex)
            ErrorDetails.from(statusProto).collect {
              case ResourceInfoDetail(contractId, type_)
                  if type_ == ErrorResource.ContractId.asString =>
                inactiveContractCallbacks.get().foreach(f => f(contractId))
            }: Unit
          case Failure(ex: io.grpc.StatusRuntimeException)
              // what even are error codes
              if ex.getMessage.contains(
                "An optional contract field with a value of Some may not be dropped during downgrading"
              ) =>
            contractDowngradeErrorCallbacks.get().foreach(f => f())
          case Failure(_) => ()
        }
      }

    result.onComplete(callCallbacksInternal)
    result.flatMap(x => {
      val (optOffset, result) = getOffsetAndResult(x)
      optOffset match {
        case None => Future.successful(result)
        case Some(offset) =>
          // Wait for the completion offset to have been processed.
          completionOffsetCallback(offset).map(_ => result)
      }
    })
  }

  private def callCallbacksOnCompletionAndWaitForOffset[T, U](
      result: Future[T]
  )(getOffsetAndResult: T => (Long, U)): Future[U] =
    callCallbacksOnCompletion(result)(x => {
      val (offset, result) = getOffsetAndResult(x)
      (Some(offset), result)
    })

  private def callCallbacksOnCompletionNoWaitForOffset[T](result: Future[T]): Future[T] = {
    callCallbacksOnCompletion(result)(x => (None, x))
  }

  private def verifyEnoughExtraTrafficRemains(
      synchronizerId: SynchronizerId,
      commandPriority: CommandPriority,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    trafficBalanceService
      .fold(Future.unit)(trafficBalanceService => {
        trafficBalanceService.lookupReservedTraffic(synchronizerId).flatMap {
          case None => Future.unit
          case Some(reservedTraffic) =>
            trafficBalanceService.lookupAvailableTraffic(synchronizerId).flatMap {
              case None => Future.unit
              case Some(availableTraffic) =>
                if (
                  availableTraffic <= reservedTraffic.unwrap && commandPriority == CommandPriority.Low
                )
                  throw Status.ABORTED
                    .withDescription(
                      s"Traffic balance below reserved traffic amount ($availableTraffic < $reservedTraffic)"
                    )
                    .asRuntimeException()
                else Future.unit
            }
        }
      })
  }

  // When using submitAndWaitForTransaction with a command whose resulting transaction is
  // not visible to the submitting parties, one receives `TRANSACTION_NOT_FOUND`. In case, you run into this consider using
  // one of the other methods in this class that rely on submitAndWaitForTransactionTree instead.

  /** Produce a builder for a command submission.
    *
    * `update` can be one of three things:
    *
    *  1. an ordinary [[Command]] or [[Seq]] thereof,
    *     2. an [[Update]], or
    *     3. the result of [[Contract.Has#exercise]].
    *
    * Supply extra arguments to the builder with the `with*` and `no*` methods.
    * At minimum, you must supply a deduplication strategy with
    * [[submit#withDedup]] or [[submit#noDedup]]; that and other required steps
    * result in compiler errors to the `yield*` methods if they are missing.
    *
    * Finalize the builder with one of the `yield*` methods to actually perform
    * the command submission.  Different calls will compile depending on what
    * `update` you supplied; for example, [[submit#yieldResult]] cannot be used
    * with a plain `Command`, but works with the other two options.
    * [[submit#yieldUnit]] and [[submit#yieldTransaction]] can always be used.
    *
    * {{{
    *  submit(Seq(actor), Seq.empty,
    *         someContract.exercise(_.exerciseFoo(42)))
    *    .withDedup(CommandId(...), task.offset)
    *    .yieldResult(): Future[Exercised[FooResult]]
    * }}}
    */
  def submit[C, DomId](
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: C,
      priority: CommandPriority = CommandPriority.Low,
      deadline: Option[NonNegativeFiniteDuration] = None,
  )(implicit assignment: UpdateAssignment[C, DomId]): submit[C, Any, DomId] =
    new submit(
      actAs,
      readAs,
      update,
      (),
      assignment.run(update),
      DisclosedContracts(),
      priority,
      deadline,
    )

  final class submit[C, CmdId, DomId] private[SpliceLedgerConnection] (
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      update: C,
      commandIdDeduplicationOffset: CmdId,
      synchronizerId: DomId,
      disclosedContracts: DisclosedContracts,
      priority: CommandPriority,
      deadline: Option[NonNegativeFiniteDuration] = None,
      preferredPackageIds: Seq[String] = Seq.empty,
  ) {
    private type DedupNotSpecifiedYet = CmdId =:= Any
    private type SynchronizerIdRequired = DomId <:< SynchronizerId

    private[this] def copy[CmdId0, DomId0](
        commandIdDeduplicationOffset: CmdId0 = this.commandIdDeduplicationOffset,
        synchronizerId: DomId0 = this.synchronizerId,
        disclosedContracts: DisclosedContracts = this.disclosedContracts,
        deadline: Option[NonNegativeFiniteDuration] = this.deadline,
        preferredPackageIds: Seq[String] = this.preferredPackageIds,
    ): submit[C, CmdId0, DomId0] =
      new submit(
        actAs,
        readAs,
        update,
        commandIdDeduplicationOffset,
        synchronizerId,
        disclosedContracts,
        priority,
        deadline,
        preferredPackageIds,
      )

    def withDedup(commandId: CommandId, deduplicationOffset: Long)(implicit
        cid: DedupNotSpecifiedYet
    ): submit[C, (CommandId, Long), DomId] =
      copy(
        commandIdDeduplicationOffset = (commandId, deduplicationOffset)
      )

    def withPreferredPackage(packageIds: Seq[String]): submit[C, CmdId, DomId] = {
      copy(
        preferredPackageIds = packageIds
      )
    }

    def withDedup(commandId: CommandId, deduplicationConfig: DedupConfig)(implicit
        cid: DedupNotSpecifiedYet
    ): submit[C, (CommandId, DedupConfig), DomId] =
      copy(
        commandIdDeduplicationOffset = (commandId, deduplicationConfig)
      )

    def noDedup(implicit cid: DedupNotSpecifiedYet): submit[C, Unit, DomId] =
      copy(commandIdDeduplicationOffset = ())

    def withSynchronizerId(
        synchronizerId: SynchronizerId,
        disclosedContracts: DisclosedContracts = DisclosedContracts(),
    )(implicit
        // if you statically know you have NE, use withDisclosedContracts instead
        notNE: disclosedContracts.type <:!< DisclosedContracts.NE
    ): submit[C, CmdId, SynchronizerId] =
      copy(
        synchronizerId = synchronizerId,
        disclosedContracts = disclosedContracts assertOnDomain synchronizerId,
      )

    def withDisclosedContracts(disclosedContracts: DisclosedContracts)(implicit
        dcAllowed: WithDisclosedContracts[C, DomId, disclosedContracts.type]
    ): submit[C, CmdId, SynchronizerId] =
      copy(
        synchronizerId = dcAllowed.inferDomain(update, synchronizerId, disclosedContracts),
        disclosedContracts = disclosedContracts,
      )

    def yieldUnit()(implicit
        tc: TraceContext,
        dom: SynchronizerIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
    ): Future[Unit] = go()

    @annotation.nowarn("cat=unused&msg=pickT")
    def yieldResult[Z]()(implicit
        tc: TraceContext,
        dom: SynchronizerIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
        pickT: YieldResult[C, Z],
        result: SubmitResult[C, Z],
    ): Future[Z] = go()

    @annotation.nowarn("cat=unused&msg=pickT")
    def yieldResultAndOffset[Z]()(implicit
        tc: TraceContext,
        dom: SynchronizerIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
        pickT: YieldResult[C, Z],
        result: SubmitResult[C, (Long, Z)],
    ): Future[(Long, Z)] =
      go()

    private[this] def go[Z]()(implicit
        tc: TraceContext,
        dom: SynchronizerIdRequired,
        dedup: SubmitDedup[CmdId],
        commandOut: SubmitCommands[C],
        result: SubmitResult[C, Z],
    ): Future[Z] = {
      verifyEnoughExtraTrafficRemains(synchronizerId, priority)
        .map(_ => commandOut.run(update).toList)
        .flatMap { commands =>
          import LedgerClient.SubmitAndWaitFor as WF
          import SubmitResult.*
          val (commandId, deduplicationConfig) = dedup.split(commandIdDeduplicationOffset)

          def clientSubmit[W, U](waitFor: WF[W])(getOffsetAndResult: W => (Long, U)): Future[U] =
            callCallbacksOnCompletionAndWaitForOffset(
              commandCircuitBreaker
                .withCircuitBreaker(
                  client.submitAndWait(
                    synchronizerId =
                      disclosedContracts.overwriteDomain(synchronizerId).toProtoPrimitive,
                    userId = userId,
                    commandId = commandId,
                    deduplicationConfig = deduplicationConfig,
                    actAs = actAs.map(_.toProtoPrimitive),
                    readAs = readAs.map(_.toProtoPrimitive),
                    commands = commands,
                    disclosedContracts = disclosedContracts,
                    waitFor = waitFor,
                    deadline = deadline,
                    preferredPackageIds = preferredPackageIds,
                  )
                )
                .recover { case ex: CircuitBreakerOpenException =>
                  // Expose a bit more info and turn it into our standard exceptions
                  throw Status.ABORTED
                    .withDescription(
                      s"Command submission aborted by circuit breaker due to too many successive failures, next attempt in ${ex.remainingDuration.toSeconds}s"
                    )
                    .asRuntimeException
                }
            )(getOffsetAndResult)

          @annotation.tailrec
          def interpretResult[C0, Z0](update: C0, result: SubmitResult[C0, Z0]): Future[Z0] =
            result match {
              case _: Ignored =>
                clientSubmit(WF.CompletionOffset)(offset => (offset, (): Z0))
              case k: ResultAndOffset[t, Z0] =>
                for {
                  tree <- clientSubmit(WF.TransactionTree)(tx => (tx.getOffset, tx))
                } yield k.continue(
                  tree.getOffset,
                  decodeExerciseResult(update, tree),
                )
              case wrapper: Contramap[C0, u, Z0] =>
                interpretResult(wrapper.g(update), wrapper.rec)
            }

          interpretResult(update, result)
        }
    }
  }

  def submitReassignmentAndWaitNoDedup(
      submitter: PartyId,
      command: LedgerClient.ReassignmentCommand,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val commandId = UUID.randomUUID().toString()
    logger.debug(s"reassignment $commandId is for $command")

    ledgerEnd().flatMap { ledgerEnd =>
      val (ks, completion) = cancelIfFailed(
        client
          .completions(userId, Seq(submitter), begin = ledgerEnd)
          .wireTap(csr => logger.trace(s"completions while awaiting reassignment $commandId: $csr"))
      )(
        awaitCompletion(
          "reassignment",
          userId = userId,
          commandId = commandId,
          submissionId = commandId,
        )
      )(
        // We call the callbacks for handling stale contract errors here, but wait for the offset
        // ingestion at which the completion is reported.
        callCallbacksOnCompletionNoWaitForOffset(
          client
            .submitReassignment(
              userId,
              commandId,
              submissionId = commandId,
              submitter,
              command,
            )
            .andThen { _ =>
              logger.info(
                s"Submitted reassignment to ledger, waiting for completion: commandId: $commandId"
              )
            }
        )
      )

      retryProvider
        .waitUnlessShutdown(completion)
        .flatMap { case ((offset, _), ()) =>
          FutureUnlessShutdown.outcomeF(
            completionOffsetCallback(offset).map(_ => ())
          )
        }
        .onShutdown {
          logger.debug(
            s"shutting down while awaiting completion of reassignment $commandId; pretending a completion arrived"
          )
          ks.shutdown()
          ()
        }
    }
  }

  def prepareSubmission(
      synchronizerId: Option[SynchronizerId],
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
      verboseHashing: Boolean,
  )(implicit
      traceContext: TraceContext
  ): Future[lapi.interactive.interactive_submission_service.PrepareSubmissionResponse] = {
    client.prepareSubmission(
      synchronizerId = synchronizerId.map(_.toProtoPrimitive),
      userId = userId,
      // Command dedup with external submissions isn't required for our use atm.
      commandId = UUID.randomUUID().toString(),
      actAs = actAs.map(_.toProtoPrimitive),
      readAs = readAs.map(_.toProtoPrimitive),
      commands = commands,
      disclosedContracts = disclosedContracts,
      verboseHashing = verboseHashing,
    )
  }

  /** Execute a signed submission and wait for it to either get commited or fail.
    * Returns the update id of the resulting transaction if it succeeds.
    */
  def executeSubmissionAndWait(
      submitter: PartyId,
      preparedTransaction: lapi.interactive.interactive_submission_service.PreparedTransaction,
      partySignatures: Map[PartyId, LedgerClient.Signature],
      waitForOffset: Boolean,
  )(implicit traceContext: TraceContext): Future[String] = {
    val commandId = preparedTransaction.getMetadata.getSubmitterInfo.commandId
    val submissionId = UUID.randomUUID.toString()
    ledgerEnd().flatMap { ledgerEnd =>
      val (ks, completion) = cancelIfFailed(
        client
          .completions(userId, Seq(submitter), begin = ledgerEnd)
      )(
        awaitCompletion(
          "reassignment",
          userId = userId,
          commandId = commandId,
          submissionId = submissionId,
        )
      )(
        // We call the callbacks for handling stale contract errors here, but wait for the offset
        // ingestion at which the completion is reported.
        callCallbacksOnCompletionNoWaitForOffset[Unit](
          client
            .executeSubmission(
              preparedTransaction,
              partySignatures,
              userId = userId,
              submissionId = submissionId,
            )
            .map { _ =>
              logger.info(
                s"Submitted executeSubmission call to ledger, waiting for completion: commandId=$commandId,submissionId=${submissionId}"
              )
            }
        )
      )

      retryProvider
        .waitUnlessShutdown(completion)
        .flatMap { case ((offset, completion), ()) =>
          (if (waitForOffset) {
             FutureUnlessShutdown.outcomeF(
               completionOffsetCallback(offset).map(_ => ())
             )
           } else {
             // TODO(DACH-NY/canton-network-node#14568) Once we ingest data for external parties, block on the offset in all cases
             FutureUnlessShutdown.unit
           }).map(_ => completion.updateId)
        }
        .onShutdown {
          logger.debug(
            s"shutting down while awaiting completion of executeSubmission"
          )
          ks.shutdown()
          throw Status.UNAVAILABLE
            .withDescription("Shutting down while awaiting completion of executeSubmission")
            .asRuntimeException()
        }
    }
  }

  // simulate the completion check of command service; future only yields
  // successfully if the completion was OK
  private[this] def awaitCompletion(
      description: String,
      userId: String,
      commandId: String,
      submissionId: String,
  )(implicit
      traceContext: TraceContext
  ): Sink[LedgerClient.CompletionStreamResponse, Future[
    (Long, LedgerClient.Completion)
  ]] = {
    import io.grpc.Status.{DEADLINE_EXCEEDED, UNAVAILABLE}
    val howLongToWait = timeouts.network.asFiniteApproximation
    Flow[LedgerClient.CompletionStreamResponse]
      .completionTimeout(howLongToWait)
      .mapError { case te: concurrent.TimeoutException =>
        DEADLINE_EXCEEDED
          .withCause(te)
          .augmentDescription(
            s"timeout while awaiting completion of $description: commandId=$commandId, submissionId=$submissionId"
          )
          .asRuntimeException()
      }
      .collect {
        case LedgerClient.CompletionStreamResponse(laterOffset, completion)
            if completion.matchesSubmission(userId, commandId, submissionId) =>
          (laterOffset, completion)
      }
      .take(1)
      .wireTap(cpl =>
        logger.debug(
          s"selected completion for commandId=$commandId, submissionId=$submissionId: $cpl"
        )
      )
      .toMat(
        Sink
          .headOption[(Long, LedgerClient.Completion)]
          .mapMaterializedValue(_ map (_ map { case result @ (_, completion) =>
            if (completion.status.isOk) result
            else throw completion.status.asRuntimeException()
          } getOrElse {
            throw UNAVAILABLE
              .augmentDescription(
                s"participant stopped while awaiting completion of $description: commandId=$commandId, submissionId=$submissionId"
              )
              .asRuntimeException()
          }))
      )(Keep.right)
  }

  // run in connected to out first, *then start* fb
  // but proactively cancel the in->out graph if fb fails
  private[this] def cancelIfFailed[A, E, B](in: Source[E, ?])(out: Sink[E, Future[A]])(
      fb: => Future[B]
  ): (KillSwitch, Future[(A, B)]) = {
    val (ks, fa) = in.viaMat(KillSwitches.single)(Keep.right).toMat(out)(Keep.both).run()
    ks -> (fa zip fb.transform(
      identity,
      { t =>
        ks.abort(t)
        t
      },
    ))
  }

}

object BaseLedgerConnection {

  val DSO_PARTY_USER_METADATA_KEY: String = "sv.app.network.canton.global/dso_party"

  val SV_NAME_USER_METADATA_KEY: String = "sv.app.network.canton.global/sv_name"

  val INITIAL_ROUND_USER_METADATA_KEY: String = "sv.app.network.canton.global/initial_round"

  val SV1_INITIAL_PACKAGE_UPLOAD_METADATA_KEY: String =
    "network.canton.global/sv1_initial_package_upload"

  val DOMAIN_MIGRATION_CURRENT_MIGRATION_ID_METADATA_KEY: String =
    "network.canton.global/domain_migration_current_migration_id"
  val DOMAIN_MIGRATION_ACS_RECORD_TIME_METADATA_KEY: String =
    "network.canton.global/domain_migration_acs_snapshot_time"
  val DOMAIN_MIGRATION_DOMAIN_WAS_PAUSED_KEY: String =
    "network.canton.global/domain_migration_domain_was_paused"

  /** In a number of places we want to use a user id in a place where a `PartyString` expected, e.g.,
    * in party id hints and in workflow ids. However, the allowed set of characters is slightly different so
    * this function can be used to perform the necessary escaping. Note that PartyString is more restrictive than
    * LedgerString so this can also be used to escape to ledger strings.
    */
  def sanitizeUserIdToPartyString(userId: String): String = {
    // We escape invalid characters using _hexcode(invalidchar)
    // See https://github.com/digital-asset/daml/blob/dfc8619e35969ce30daa2427d7318bf70bb75386/daml-lf/data/src/main/scala/com/digitalasset/daml/lf/data/IdString.scala#L320
    // for allowed characters.
    userId.view.flatMap {
      case c if c >= 'a' && c <= 'z' => Seq(c)
      case c if c >= 'A' && c <= 'Z' => Seq(c)
      case c if c >= '0' && c <= '9' => Seq(c)
      case c if c == ':' || c == '-' || c == ' ' => Seq(c)
      case '_' => Seq('_', '_')
      case c =>
        '_' +: "%04x".format(c.toInt)
    }.mkString
  }

  sealed trait ActiveContractsItem
  object ActiveContractsItem {
    case class ActiveContract(
        contract: org.lfdecentralizedtrust.splice.environment.ledger.api.ActiveContract
    ) extends ActiveContractsItem

    case class IncompleteUnassign(unassign: IncompleteReassignmentEvent.Unassign)
        extends ActiveContractsItem

    case class IncompleteAssign(assign: IncompleteReassignmentEvent.Assign)
        extends ActiveContractsItem
  }
}

object SpliceLedgerConnection {

  def uniqueId: String = UUID.randomUUID.toString

  /** Abstract representation of a command-id for deduplication.
    *
    * @param methodName    : fully-classified name of the method whose calls should be deduplicated,
    *                      e.g., "org.lfdecentralizedtrust.splice.directory.createDirectoryEntry". DON'T USE [[io.functionmeta.functionFullName]] here,
    *                      as it is not consistent across updates and restarts.
    * @param parties       : list of parties whose method calls should be considered distinct,
    *                      e.g., "Seq(directoryProvider)"
    * @param discriminator : additional discriminator for method calls,
    *                      e.g., Seq("digitalasset.splice") in case of deduplicating directory entry requests relating to directory name "digitalasset.splice". Beware of naive concatenation
    *                      strings for discriminators. Always ensure that the encoding is injective.
    */
  case class CommandId(
      methodName: String,
      parties: Seq[PartyId],
      discriminator: Seq[String] = Seq.empty,
  ) extends PrettyPrinting {
    require(!methodName.contains('_'))

    override def pretty: Pretty[this.type] =
      prettyOfString(_.commandIdForSubmission)

    // NOTE: avoid changing this computation, as otherwise some commands might not get properly deduplicated
    // on an app upgrade.
    def commandIdForSubmission: String = {
      def discriminatorString: Seq[String] = discriminator.toList match {
        case Nil => Seq("")
        case head :: Nil =>
          Seq(head) // keep old behavior for upgrades, when discriminator was a single string
        case list => list.flatMap(str => Seq(str.length.toString, str))
      }
      val str = parties
        .map(_.toProtoPrimitive)
        .prepended(
          parties.length.toString
        ) // prepend length to avoid suffixes interfering with party mapping, e.g., otherwise we have
        // CommandId("myMethod", Seq(alice), "bob").commandIdForSubmission == CommandId("myMethod", Seq(alice,bob), "").commandIdForSubmission
        .appendedAll(discriminatorString)
        .mkString("/")
      // Digest is not thread safe, create a new one each time.
      val hashFun = MessageDigest.getInstance("SHA-256")
      val hash = hashFun.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
      s"${methodName}_$hash"
    }
  }
  object CommandId {
    def apply(methodName: String, parties: Seq[PartyId], discriminator: String): CommandId =
      CommandId(methodName, parties, Seq(discriminator))
  }

  def decodeExerciseResult[T](
      update: Update[T],
      transaction: Transaction,
  ): T = {
    val rootEventIds = transaction.getRootNodeIds.asScala.toSeq
    if (rootEventIds.size == 1) {
      val eventByIds = transaction.getEventsById.asScala
      val event = eventByIds(rootEventIds(0))
      update.foldUpdate[T](
        new Update.FoldUpdate[T, T] {
          override def created[CtId](create: Update.CreateUpdate[CtId, T]): T = {
            val createdEvent = event match {
              case created: CreatedEvent => created
              case _ =>
                throw new IllegalArgumentException(s"Expected CreatedEvent but got $event")
            }
            create.k(Created.fromEvent(create.createdContractId, createdEvent))
          }

          override def exercised[R](exercise: Update.ExerciseUpdate[R, T]): T = {
            val exercisedEvent = event match {
              case exercised: ExercisedEvent => exercised
              case _ =>
                throw new IllegalArgumentException(s"Expected ExercisedEvent but got $event")
            }
            exercise.k(Exercised.fromEvent(exercise.returnTypeDecoder, exercisedEvent))
          }
        }
      )
    } else {
      throw new IllegalArgumentException(
        s"Expected exactly one root event id but got ${rootEventIds.size}"
      )
    }
  }

  @implicitNotFound(
    "${CmdId} isn't a deduplication configuration or Unit. Did you call withDedup or noDedup?"
  )
  final case class SubmitDedup[-CmdId](
      private[SpliceLedgerConnection] val split: CmdId => (String, DedupConfig)
  )
  object SubmitDedup {
    implicit val dedupOffset: SubmitDedup[(CommandId, Long)] = SubmitDedup { case (cid, offset) =>
      (cid.commandIdForSubmission, DedupOffset(offset))
    }
    implicit val dedupConfig: SubmitDedup[(CommandId, DedupConfig)] = SubmitDedup {
      case (cid, dc) =>
        (cid.commandIdForSubmission, dc)
    }
    implicit val noDedup: SubmitDedup[Unit] =
      SubmitDedup(_ => (SpliceLedgerConnection.uniqueId, NoDedup))
  }

  /** Some `update`s can determine the domain ID immediately; this typeclass
    * determines when this has happened.
    */
  final case class UpdateAssignment[-C, +DomId](private[SpliceLedgerConnection] val run: C => DomId)
  object UpdateAssignment extends UpdateAssignmentLow {
    implicit val assigned
        : UpdateAssignment[Contract.Exercising[AssignedContract[?, ?], ?], SynchronizerId] =
      UpdateAssignment(_.origin.domain)
  }
  sealed abstract class UpdateAssignmentLow {
    implicit val noInfo: UpdateAssignment[Any, Unit] = UpdateAssignment(Function const (()))
  }

  @implicitNotFound(
    "withDisclosedContracts can only be used when ${C} is .exercise on an AssignedContract, or ${DomId} is Unit and ${Arg} is statically non-empty"
  )
  final case class WithDisclosedContracts[-C, -DomId, -Arg](
      private[SpliceLedgerConnection] val inferDomain: (C, DomId, Arg) => SynchronizerId
  )
  object WithDisclosedContracts {
    @annotation.nowarn("cat=unused&msg=C")
    implicit def exercised[C](implicit
        C: UpdateAssignment[C, SynchronizerId] // proves that SynchronizerId derives directly from C
    ): WithDisclosedContracts[C, SynchronizerId, DisclosedContracts] =
      WithDisclosedContracts((_, synchronizerId, _) => synchronizerId)
    implicit val nonEmpty: WithDisclosedContracts[Any, Unit, DisclosedContracts.NE] =
      WithDisclosedContracts((_, _, dc) => dc.assignedDomain)
  }

  /** A type-determining typeclass for [[SpliceLedgerConnection#submit#yieldResult]].
    * That method requires an effective functional dependency `C -> Z`, but
    * [[SubmitCommands]] cannot supply that.  The fundep is supplied by this
    * typeclass instead.
    */
  @implicitNotFound("${C} doesn't contain an Update that can yield a result")
  sealed abstract class YieldResult[-C, +Z]
  object YieldResult {
    private[this] object OnlyInstance extends YieldResult[Any, Nothing]
    implicit def update[T]: YieldResult[Update[T], T] = OnlyInstance
    implicit def exercising[T]: YieldResult[Contract.Exercising[Any, T], T] =
      OnlyInstance
  }

  /** Just a folder for the commands in an `update` supplied to `#submit`. */
  @implicitNotFound(
    "${C} isn't a single update, contract exercise or list of commands. The update argument to submit must be one of these"
  )
  final case class SubmitCommands[-C](private[SpliceLedgerConnection] val run: C => Seq[Command])
  object SubmitCommands {
    implicit val single: SubmitCommands[HasCommands] = SubmitCommands(_.commands().asScala.toSeq)
    implicit val multiple: SubmitCommands[Seq[HasCommands]] =
      SubmitCommands(cs => HasCommands.toCommands(cs.asJava).asScala.toSeq)
    implicit val exercise: SubmitCommands[Contract.Exercising[Any, ?]] =
      SubmitCommands(e => single.run(e.update))
  }

  @implicitNotFound("${C} can't be interpreted to produce ${Z} from the command service")
  sealed abstract class SubmitResult[-C, +Z]
  object SubmitResult {
    private[SpliceLedgerConnection] final class Ignored extends SubmitResult[Any, Unit]
    implicit val Ignored: SubmitResult[Any, Unit] = new Ignored
    implicit def resultAndOffset[T]: SubmitResult[Update[T], (Long, T)] = new ResultAndOffset()
    implicit def onlyResult[T]: SubmitResult[Update[T], T] = new ResultAndOffset((_, t) => t)
    implicit def exercising[T, Z](implicit
        rec: SubmitResult[Update[T], Z]
    ): SubmitResult[Contract.Exercising[Any, T], Z] = new Contramap(_.update, rec)

    private[SpliceLedgerConnection] final class ResultAndOffset[T, +Z](
        val continue: (Long, T) => Z = (_: Long, _: T)
    ) extends SubmitResult[Update[T], Z]

    private[SpliceLedgerConnection] final class Contramap[-C, U, +Z](
        val g: C => U,
        val rec: SubmitResult[U, Z],
    ) extends SubmitResult[C, Z]
  }
}

sealed trait CommandPriority extends PrettyPrinting {
  override def pretty: Pretty[CommandPriority.this.type] = prettyOfString(_.name)
  def name: String
}

object CommandPriority {
  case object High extends CommandPriority { val name = "High" }
  case object Low extends CommandPriority { val name = "Low" }
}
