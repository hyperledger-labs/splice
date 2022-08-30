Monitoring
==========

* How to aggregate and read logs?

  * aggregate logs from your app backend and validator node into your log
    aggregator of choice (e.g., using https://cloud.google.com/logging/docs)
  * consider aggregating client side logs for your app user UI:
    https://www.loggly.com/blog/best-practices-for-client-side-logging-and-error-handling-in-react/
  * consider adding trace-ids to your app backend to correlate log entries
    caused by the same request (see
    https://docs.daml.com/canton/usermanual/monitoring.html#tracing for an
    example of how trace-ids are used in Canton)

* How to aggregate and inspect metrics?

  * aggregate your metrics using your metric store of choice
  * ensure your app backend exposes metrics
  * familiarize yourself with the metrics exposed by a validator

    * Canton participant metrics: https://docs.daml.com/canton/usermanual/monitoring.html#metrics
    * Validator app metrics: WIP

.. todo::

  Improve docs for what metrics a validator node exposes.
  Also document the tech stack that we use for CC as inspiration.

* Required maintenance operations for participant and domain nodes?

  * monitor DB health, and intervene in case there is a problem

