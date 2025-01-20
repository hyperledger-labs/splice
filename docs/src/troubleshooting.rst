..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _troubleshooting:

Troubleshooting
===============

This section provides an overview of which information can be collected to debug issues in a Canton Network Node.

Where to find logs
------------------

**When launched locally**, splice-node will create a ``log/`` directory located at the root of the repository and log into ``canton_network.log``.
Canton logs into ``canton.log``.

.. note::
    The default log level, initially set to Debug, can be changed using the ``--log-level-canton`` flag, for example: ``splice-node --config "${OUTPUT_CONFIG}" --log-level-canton=DEBUG ...``

**When the node is launched in a kubernetes cluster**, we recommend to setup a log collector so that you can capture logs of at least the last day. For now, the default log level is set to Debug.

We recommend to use ``lnav`` to read the logs. A guideline is provided in `this documentation <https://docs.daml.com/canton/usermanual/troubleshooting_guide.html#using-lnav-to-view-log-files>`_.

.. note::
    Logging in kubernetes (note that this only provides logs for a limited timeframe):

    - ``kubectl describe pod <pod-name>`` to get a detailed status of the given pod,
    - ``kubectl logs <pod-name> -n <namespace-name>`` or ``kubectl logs -l app=<app-name> -n <namespace-name> --tail=-1`` to get logs for a given pod in a given namespace.

Debugging issues in Web UIs
---------------------------

When facing an issue related to connectivity problems, if you are using chrome or firefox based browser:

1. Go to the right of the address bar in your browser, go on the settings and then ``More tools -> Developer tools`` (or by hitting Ctrl + Shift + i). Your browser developer tools window opens.

2. Click the ``Network`` tab.

3. Enable the ``Preserve or Persist log`` check box.

4. While the console remains open, **reproduce the issue you want to report**.

5. Once you are done, right-click and select ``Save all as HAR``

6. Name the file with the description of your issue and save it.

Once you are done reiterate 2-6 by clicking the ``Console`` tab instead of the ``Network`` tab.

Configurations
--------------

Another thing which is often quite helpful to diagnose issue is to collect all configurations.

- The application configuration files when running locally,
- The helm values when using the helm charts (``helm get values -n <namespace> <chartname>``),
- The environment variables when using the docker container but not the helm charts.

In addition to that also check the version you are using and the network (dev/test/mainnet) you are running against.
