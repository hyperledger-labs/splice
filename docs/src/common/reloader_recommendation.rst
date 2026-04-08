..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. tip::

    We recommend installing `Stakater Reloader <https://github.com/stakater/Reloader>`_,
    which automatically performs rolling restarts of pods when their referenced Secrets or ConfigMaps change.
    Splice Helm charts include the ``reloader.stakater.com/auto: "true"`` annotation by default.
    If you do not use Reloader, the annotation is harmless and will be ignored.
    To remove it, set ``enableReloader: false`` in your Helm values file.
