// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.concurrent

import cats.syntax.either.*
import com.daml.metrics.ExecutorServiceMetrics
import com.digitalasset.canton.checked
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.lifecycle.ClosingException
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.Logger

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate
import scala.concurrent.{ExecutionContextExecutor, blocking}
import scala.util.chaining.*

/** Factories and utilities for dealing with threading.
  */
object Threading {

  /** Creates a singled threaded scheduled executor.
    * @param name
    *   used for created threads. Prefer dash separated names. `-{n}` will be appended.
    * @param logger
    *   where uncaught exceptions are logged
    */
  def singleThreadScheduledExecutor(
      name: String,
      logger: Logger,
  ): ScheduledExecutorService = {
    val executor = new ScheduledThreadPoolExecutor(
      1,
      threadFactory(name, logger, exitOnFatal = true),
    )
    // we don't want tasks scheduled far in the future to prevent a clean shutdown
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    executor
  }

  /** Creates a singled threaded scheduled executor with maximum thread pool size = 1.
    * @param name
    *   used for created threads. Prefer dash separated names.
    * @param logger
    *   where uncaught exceptions are logged
    */
  def singleThreadedExecutor(
      name: String,
      logger: Logger,
  ): ExecutionContextIdlenessExecutorService = {
    val executor = new ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable](),
      threadFactory(name, logger, exitOnFatal = true),
    )
    new ThreadPoolIdlenessExecutorService(
      executor,
      createReporter(name, logger, exitOnFatal = true)(_),
      name,
    )
  }

  /** @param exitOnFatal
    *   terminate the JVM on fatal errors. Enable this in production to prevent data corruption by
    *   termination of specific threads.
    */
  private def threadFactory(
      name: String,
      logger: Logger,
      exitOnFatal: Boolean,
  ): ThreadFactory =
    new ThreadFactoryBuilder()
      .setUncaughtExceptionHandler(createUncaughtExceptionHandler(logger, exitOnFatal))
      .setNameFormat(s"$name-%d")
      .setDaemon(true)
      .build()

  /** @param exitOnFatal
    *   terminate the JVM on fatal errors. Enable this in production to prevent data corruption by
    *   termination of specific threads.
    */
  private def createUncaughtExceptionHandler(
      logger: Logger,
      exitOnFatal: Boolean,
  ): Thread.UncaughtExceptionHandler =
    (t: Thread, e: Throwable) => createReporter(t.getName, logger, exitOnFatal)(e)

  /** @param exitOnFatal
    *   terminate the JVM on fatal errors. Enable this in production to prevent data corruption by
    *   termination of specific threads.
    */
  def createReporter(name: String, logger: Logger, exitOnFatal: Boolean)(
      throwable: Throwable
  ): Unit = {
    if (exitOnFatal) doExitOnFatal(name, logger)(throwable)
    throwable match {
      case ex: io.grpc.StatusRuntimeException
          if ex.getStatus.getCode == io.grpc.Status.Code.CANCELLED =>
        logger.info(s"Grpc channel cancelled in $name.", ex)
      case ClosingException(_) =>
        logger.info(s"Unclean shutdown due to cancellation in $name.", throwable)
      case _: Throwable =>
        logger.error(s"A fatal error has occurred in $name. Terminating thread.", throwable)
    }
  }

  private def doExitOnFatal(name: String, logger: Logger)(throwable: Throwable): Unit =
    throwable match {
      case _: LinkageError | _: VirtualMachineError =>
        // Output the error reason both to stderr and the logger,
        // because System.exit tends to terminate the JVM before everything has been output.
        Console.err.println(
          s"A fatal error has occurred in $name. Terminating immediately.\n${ErrorUtil.messageWithStacktrace(throwable)}"
        )
        Console.err.flush()
        logger.error(s"A fatal error has occurred in $name. Terminating immediately.", throwable)
        System.exit(-1)
      case _: Throwable => // no fatal error, nothing to do
    }

  def newExecutionContext(
      name: String,
      logger: Logger,
  ): ExecutionContextIdlenessExecutorService =
    newExecutionContext(name, logger, None)

  def newExecutionContext(
      name: String,
      logger: Logger,
      metrics: ExecutorServiceMetrics,
  ): ExecutionContextIdlenessExecutorService =
    newExecutionContext(name, logger, Some(metrics))

  def newExecutionContext(
      name: String,
      logger: Logger,
      maybeMetrics: Option[ExecutorServiceMetrics],
  ): ExecutionContextIdlenessExecutorService =
    newExecutionContext(
      name,
      logger,
      maybeMetrics,
      detectNumberOfThreads(logger),
    )

  /** Yields an `ExecutionContext` like `scala.concurrent.ExecutionContext.global`, except that it
    * has its own thread pool.
    *
    * @param exitOnFatal
    *   terminate the JVM on fatal errors. Enable this in production to prevent data corruption by
    *   termination of specific threads.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.AsInstanceOf"))
  def newExecutionContext(
      name: String,
      logger: Logger,
      maybeMetrics: Option[ExecutorServiceMetrics],
      parallelism: PositiveInt,
      maxExtraThreads: PositiveInt = PositiveInt.tryCreate(256),
      exitOnFatal: Boolean = true,
  ): ExecutionContextIdlenessExecutorService = {
    val reporter = createReporter(name, logger, exitOnFatal)(_)
    val handler = ((_, cause) => reporter(cause)): Thread.UncaughtExceptionHandler

    val threadFactoryConstructor = Class
      .forName("scala.concurrent.impl.ExecutionContextImpl$DefaultThreadFactory")
      .getDeclaredConstructor(
        classOf[Boolean],
        classOf[Int],
        classOf[String],
        classOf[Thread.UncaughtExceptionHandler],
      )
    threadFactoryConstructor.setAccessible(true)
    val threadFactory = threadFactoryConstructor
      .newInstance(Boolean.box(true), Int.box(maxExtraThreads.value), name, handler)
      .asInstanceOf[ForkJoinPool.ForkJoinWorkerThreadFactory]

    val forkJoinPool = createForkJoinPool(parallelism, threadFactory, handler, logger)
    val executorService =
      maybeMetrics.fold(forkJoinPool: ExecutorService)(
        _.monitorExecutorService(name, forkJoinPool)
      )

    new ForkJoinIdlenessExecutorService(forkJoinPool, executorService, reporter, name)
  }

  /** Minimum parallelism of ForkJoinPool. Currently greater than one to work around a bug that
    * prevents creation of new threads to compensate blocking tasks.
    */
  val minParallelismForForkJoinPool: PositiveInt = PositiveInt.tryCreate(3)

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private def createForkJoinPool(
      parallelism: PositiveInt,
      threadFactory: ForkJoinPool.ForkJoinWorkerThreadFactory,
      handler: Thread.UncaughtExceptionHandler,
      logger: Logger,
  ): ForkJoinPool = {
    val tunedParallelism =
      if (parallelism >= minParallelismForForkJoinPool) parallelism
      else {
        // The calculation of running threads in ForkJoinPool may overestimate the actual number.
        // As a result, we need to request at least minParallelism threads to get at least 1 (with high probability).
        // The pool may still run out of threads, but the probability is much lower.
        logger.info(
          s"Creating ForkJoinPool with parallelism = $minParallelismForForkJoinPool (instead of $parallelism) to avoid starvation."
        )
        minParallelismForForkJoinPool
      }

    try {
      val java11ForkJoinPoolConstructor = classOf[ForkJoinPool].getConstructor(
        classOf[Int],
        classOf[ForkJoinPool.ForkJoinWorkerThreadFactory],
        classOf[Thread.UncaughtExceptionHandler],
        classOf[Boolean],
        classOf[Int],
        classOf[Int],
        classOf[Int],
        classOf[Predicate[_]],
        classOf[Long],
        classOf[TimeUnit],
      )

      java11ForkJoinPoolConstructor.newInstance(
        Int.box(tunedParallelism.unwrap),
        threadFactory,
        handler,
        Boolean.box(true),
        Int.box(tunedParallelism.unwrap),
        Int.box(Int.MaxValue),
        //
        // Choosing tunedParallelism here instead of the default of 1.
        // With the default, we would get only 1 running thread in the presence of blocking calls.
        Int.box(tunedParallelism.unwrap),
        null,
        Long.box(60),
        TimeUnit.SECONDS,
      )
    } catch {
      case _: NoSuchMethodException =>
        logger.warn(
          "Unable to create ForkJoinPool of Java 11. " +
            "Using fallback instead, which has been tested less than the default one. " +
            "Do not use this setting in production."
        )
        new ForkJoinPool(tunedParallelism.unwrap, threadFactory, handler, true)
    }
  }

  def directExecutionContext(logger: Logger): ExecutionContextExecutor = DirectExecutionContext(
    logger
  )

  // None only when detection code was never run
  private val detectedNumberOfThreads = new AtomicReference[Option[PositiveInt]](None)

  /** Detects the number of threads the same way as `scala.concurrent.impl.ExecutionContextImpl`,
    * except that system property values like 'x2' are not supported.
    *
    * This will run once and cache the results
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def detectNumberOfThreads(logger: Logger): PositiveInt =
    detectedNumberOfThreads.get() match {
      case Some(numberOfThreads) => numberOfThreads
      case None =>
        def getIntProperty(name: String): Option[PositiveInt] =
          for {
            strProperty <- Option(System.getProperty(name))
            parsedValue <- Either
              .catchOnly[NumberFormatException](strProperty.toInt)
              .leftMap(_ =>
                logger.warn(
                  show"Unable to parse '-D${strProperty.singleQuoted}' as value of ${name.unquoted}. Ignoring value."
                )
              )
              .toOption
            value <-
              if (parsedValue >= 1) Some(PositiveInt.tryCreate(parsedValue))
              else {
                logger.warn(
                  show"The value $parsedValue of '-D${name.unquoted}' is less than 1. Ignoring value."
                )
                None
              }
          } yield value

        var numThreads = getIntProperty(numThreadsProp) match {
          case Some(n) =>
            logger.info(s"Deriving $n as number of threads from '-D$numThreadsProp'.")
            n
          case None =>
            // Guaranteed to be positive: https://docs.oracle.com/javase/8/docs/api/java/lang/Runtime.html#availableProcessors--
            PositiveInt.tryCreate(checked(sys.runtime.availableProcessors())).tap { n =>
              logger.info(
                s"Deriving $n as number of threads from 'sys.runtime.availableProcessors()'. " +
                  s"Please use '-D$numThreadsProp' to override."
              )
            }
        }

        getIntProperty(minThreadsProp).foreach { minThreads =>
          if (numThreads < minThreads) {
            logger.info(
              s"Applying '-D$minThreadsProp' to increase number of threads from $numThreads to $minThreads."
            )
            numThreads = minThreads
          }
        }

        getIntProperty(maxThreadsProp).foreach { maxThreads =>
          if (numThreads > maxThreads) {
            logger.info(
              s"Applying '-D$maxThreadsProp' to decrease number of threads from $numThreads to $maxThreads."
            )
            numThreads = maxThreads
          }
        }
        detectedNumberOfThreads.set(Some(numThreads))
        numThreads
    }

  private val numThreadsProp = "scala.concurrent.context.numThreads"
  private val minThreadsProp = "scala.concurrent.context.minThreads"
  private val maxThreadsProp = "scala.concurrent.context.maxThreads"
  val threadingProps: List[String] = List(numThreadsProp, minThreadsProp, maxThreadsProp)

  @SuppressWarnings(Array("com.digitalasset.canton.RequireBlocking"))
  def sleep(millis: Long, nanos: Int = 0): Unit = blocking(Thread.sleep(millis, nanos))
}
