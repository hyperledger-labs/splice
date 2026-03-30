..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Deployment

        - We've added support for `reloader annotation <https://github.com/stakater/reloader>`, which performs a rolling
          restart of our apps on secret/configmap change. The integration is enabled by
          default. You can disable it by setting enableReloader to false in your values.yaml file.
          Please note that reloader needs to be installed separately.
