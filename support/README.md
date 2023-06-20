# Support Tooling, Tips and Tricks

This README serves as a place for us to collect support tools, tips and tricks.

## Viewing customer-provided logs using lnav

### SBI

As of 2023-06-20, SBI's logs typically come as a single `.txz` file containing many log files in a directory hierarchy
aligned to the pods started in their k8s deployment. The logs lines look like
```
2023-06-20T01:03:22.149107199Z stdout F {"@timestamp":"2023-06-20T01:03:22.147Z","@version":"1","message":"Initialization of sv failed","logger_name":"c.daml.network.sv.SvApp:SV=sv","thread_name":"canton-env-execution-context-21","level":"ERROR","level_value":40000,"stack_trace":"io.grpc.StatusRuntimeException: UNAVAILABLE: DOMAIN_IS_NOT_AVAILABLE(1,0): Cannot connect to domain Domain 'global'\n\tat io.grpc.Status.asRuntimeException(Status.java:535)\n\tat io.grpc.stub.ClientCalls$UnaryStreamToFuture.onClose(ClientCalls.java:534)\n\tat io.grpc.internal.ClientCallImpl.closeObserver(ClientCallImpl.java:562)\n\tat io.grpc.internal.ClientCallImpl.access$300(ClientCallImpl.java:70)\n\tat io.grpc.internal.ClientCallImpl$ClientStreamListenerImpl$1StreamClosed.runInternal(ClientCallImpl.java:743)\n\tat io.grpc.internal.ClientCallImpl$ClientStreamListenerImpl$1StreamClosed.runInContext(ClientCallImpl.java:722)\n\tat io.grpc.internal.ContextRunnable.run(ContextRunnable.java:37)\n\tat io.grpc.internal.SerializingExecutor.run(SerializingExecutor.java:133)\n\tat com.daml.executors.QueueAwareExecutorService$TrackingRunnable.run(QueueAwareExecutorService.scala:98)\n\tat com.daml.metrics.InstrumentedExecutorServiceMetrics$InstrumentedExecutorService$InstrumentedRunnable.run(InstrumentedExecutorServiceMetrics.scala:202)\n\tat java.base/java.util.concurrent.ForkJoinTask$RunnableExecuteAction.exec(ForkJoinTask.java:1426)\n\tat java.base/java.util.concurrent.ForkJoinTask.doExec(ForkJoinTask.java:290)\n\tat java.base/java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(ForkJoinPool.java:1020)\n\tat java.base/java.util.concurrent.ForkJoinPool.scan(ForkJoinPool.java:1656)\n\tat java.base/java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1594)\n\tat java.base/java.util.concurrent.ForkJoinWorkerThread.run(ForkJoinWorkerThread.java:183)\n"}
2023-06-20T01:03:22.149466506Z stderr F Initialization of sv failed, so exiting; check the application logs for details
```
you can ignore the `stderr` ones, as they are an excerpt of the `stdout` ones with log-level `ERROR`.

Current procedure is to
1. `tar -xf` the file
2. `gunzip` the gzipped logs of interest
3. run `for file in *; sed -i 's/.*stdout F \(.*\)/\1/' $file; mv $file $(dirname $file)/$(basename $file).clog; end`
   to ensure the Canton JSON log format is properly parsed.
4. start `lnav` on the resulting files

TODO(tech-debt): package these steps up into a script, provided we get more of these logs.
