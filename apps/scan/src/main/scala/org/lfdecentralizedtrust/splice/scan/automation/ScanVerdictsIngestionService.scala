package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import org.apache.pekko.Done
import org.apache.pekko.stream.{KillSwitch, KillSwitches, Materializer, RestartSettings}
import org.apache.pekko.stream.scaladsl.{Keep, RestartSource, Sink, Source}
import org.lfdecentralizedtrust.splice.scan.config.MediatorVerdictIngestionConfig
import org.lfdecentralizedtrust.splice.scan.mediator.MediatorVerdictsClient
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object IngestionUtil {

  /** Runs an ingestion stream, returning a KillSwitch to allow for graceful shutdown.
    * The stream will automatically restart if the batchSource fails.
    * The batchSource is expected to keep track of where to start from.
    * The storeBatch function is expected to store the batch and return a Future that completes when the batch is stored,
    * to allow for backpressure and to avoid overlapping batches in case of restarts due to storeBatch failures.
    */
  def runIngestion[T, R](
      batchSource: Source[Seq[T], R],
      storeBatch: Seq[T] => Future[Seq[T]],
      restartSettings: RestartSettings,
  )(implicit ec: ExecutionContext, mat: Materializer): KillSwitch = {
    restartBatchSource(restartSettings, batchSource, storeBatch)
      .toMat(Sink.ignore)(Keep.left)
      .run()
  }

  /** Creates a source that will restart the batchSource with the given restartSettings if it fails.
    * The storeBatch function is expected to store the batch and return a Future that completes when the batch is stored,
    * to allow for backpressure and to avoid overlapping batches in case of restarts due to storeBatch failures.
    * The source will wait for the last in-flight storeBatch to complete before starting a new batch source,
    * to avoid overlapping batches in case of restarts due to storeBatch failures.
    */
  def restartBatchSource[T, Mat](
      restartSettings: RestartSettings,
      batchSource: Source[Seq[T], Mat],
      storeBatch: Seq[T] => Future[Seq[T]],
  )(implicit ec: ExecutionContext): Source[Seq[T], KillSwitch] = {
    val lastInFlightRef = new AtomicReference[Future[Done]](Future.successful(Done))
    RestartSource
      .withBackoff(restartSettings) { () =>
        // wait for the last in-flight storeBatch to complete before starting a new batch source, to avoid overlapping batches in case of restarts due to storeBatch failures
        val waitForLastInFlight = lastInFlightRef
          .getAndSet(Future.successful(Done))
          .recover { case _ =>
            Done
          } // in case of failure we don't want to block the stream from restarting

        Source.futureSource(waitForLastInFlight.map { _ =>
          batchSource.mapAsync(1) { batch =>
            val fResult = storeBatch(batch)
            // only one mapAsync at a time, one storeBatch in flight at a time, so it's safe to update the reference here
            lastInFlightRef.set(fResult.map(_ => Done))
            fResult
          }
        })
      }
      .viaMat(KillSwitches.single)(Keep.right)
  }
}

object ScanVerdictsIngestionService extends NamedLogging {
  import IngestionUtil.*
  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  /** runs the mediator verdicts ingestion stream, which will read verdicts from the mediator client and insert them into the store.
    * Use the returned KillSwitch to stop the stream gracefully.
    */
  def run(
      mediatorClient: MediatorVerdictsClient,
      config: MediatorVerdictIngestionConfig,
      store: DbScanVerdictStore,
      ingestionMetrics: ScanMediatorVerdictIngestionMetrics,
      migrationId: Long,
      synchronizerId: SynchronizerId,
  )(implicit
      tc: TraceContext,
      mat: Materializer,
      ec: ExecutionContext,
  ): KillSwitch = {
    runIngestion(
      mediatorClientSource(config, mediatorClient, store, migrationId),
      storeVerdictsBatch(store, ingestionMetrics, migrationId, synchronizerId),
      RestartSettings(
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.1,
      ),
    )
  }

  private def storeVerdictsBatch(
      store: DbScanVerdictStore,
      ingestionMetrics: ScanMediatorVerdictIngestionMetrics,
      migrationId: Long,
      synchronizerId: SynchronizerId,
  )(batch: Seq[v30.Verdict])(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[v30.Verdict]] = {
    val items: Seq[(store.VerdictT, Long => Seq[store.TransactionViewT])] =
      batch.map(toDbRowAndViews(store, migrationId, synchronizerId))
    store
      .insertVerdictAndTransactionViews(items)
      .transform {
        case Success(_) =>
          val lastRecordTime = batch.lastOption
            .flatMap(v => CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption)
            .getOrElse(CantonTimestamp.MinValue)
          ingestionMetrics.lastIngestedRecordTime.updateValue(lastRecordTime)
          ingestionMetrics.verdictCount.mark(batch.size.toLong)(MetricsContext.Empty)
          logger.info(
            s"Inserted ${batch.size} verdicts. Last ingested verdict record_time is now ${store.lastIngestedRecordTime}. Inserted verdicts: ${batch
                .map(_.updateId)}"
          )
          Success(batch)
        case Failure(ex) =>
          ingestionMetrics.errors.mark()
          Failure(ex)
      }
  }

  def toDbRowAndViews(
      store: DbScanVerdictStore,
      migrationId: Long,
      synchronizerId: SynchronizerId,
  )(
      verdict: v30.Verdict
  ): (store.VerdictT, Long => Seq[store.TransactionViewT]) = {
    val transactionRootViews = verdict.getTransactionViews.rootViews
    val resultShort: Short = DbScanVerdictStore.VerdictResultDbValue.fromProto(verdict.verdict)
    val row = new store.VerdictT(
      rowId = 0,
      migrationId = migrationId,
      domainId = synchronizerId,
      recordTime = CantonTimestamp
        .fromProtoTimestamp(verdict.getRecordTime)
        .getOrElse(throw new IllegalArgumentException("Invalid timestamp")),
      finalizationTime = CantonTimestamp
        .fromProtoTimestamp(verdict.getFinalizationTime)
        .getOrElse(throw new IllegalArgumentException("Invalid timestamp")),
      submittingParticipantUid = verdict.submittingParticipantUid,
      verdictResult = resultShort,
      mediatorGroup = verdict.mediatorGroup,
      updateId = verdict.updateId,
      submittingParties = verdict.submittingParties,
      transactionRootViews = transactionRootViews,
    )

    val mkViews: Long => Seq[store.TransactionViewT] = { rowId =>
      verdict.getTransactionViews.views.map { case (viewId, txView) =>
        val confirmingPartiesJson: Json = Json.fromValues(
          txView.confirmingParties.map { q =>
            Json.obj(
              "parties" -> Json.fromValues(q.parties.map(Json.fromString)),
              "threshold" -> Json.fromInt(q.threshold),
            )
          }
        )
        new store.TransactionViewT(
          verdictRowId = rowId,
          viewId = viewId,
          informees = txView.informees,
          confirmingParties = confirmingPartiesJson,
          subViews = txView.subViews,
        )
      }.toSeq
    }
    (row, mkViews)
  }

  /** Creates a source that streams verdicts from the mediator client, starting from the last ingested record time in the store.
    */
  def mediatorClientSource(
      config: MediatorVerdictIngestionConfig,
      mediatorClient: MediatorVerdictsClient,
      store: DbScanVerdictStore,
      migrationId: Long,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Source[Seq[v30.Verdict], (KillSwitch, scala.concurrent.Future[Done])] = {
    Source
      .future(
        store.waitUntilInitialized.flatMap(_ =>
          store
            .maxVerdictRecordTime(migrationId)
            .map(_.getOrElse(CantonTimestamp.MinValue))
        )
      )
      .map { ts =>
        logger.info(s"Streaming verdicts starting from $ts")
        Some(ts)
      }
      .flatMapConcat(mediatorClient.streamVerdicts)
      .groupedWithin(
        math.max(1, config.batchSize),
        config.batchMaxWait.underlying,
      )
      .viaMat(KillSwitches.single)(Keep.right)
      .watchTermination() { case (ks, done) => (ks: KillSwitch, done) }
  }
}
