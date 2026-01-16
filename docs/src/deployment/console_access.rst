..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _console_access:

Getting console access to Canton nodes
======================================

For more involved debugging and disaster recovery, direct access to the console of a Canton node (participant, sequencer, mediator) might be required.
Steps to obtain such access:

Requirements:

- Direct access to the Canton node process
- Canton binary

Once you see the following banner for the console you have successfully gained access

.. code-block:: text

      _____            _
     / ____|          | |
    | |     __ _ _ __ | |_ ___  _ __
    | |    / _` | '_ \| __/ _ \| '_ \
    | |___| (_| | | | | || (_) | | | |
     \_____\__,_|_| |_|\__\___/|_| |_|

     Welcome to Canton!

Participant console
+++++++++++++++++++

1. Obtain an authentication token as specified in `the Canton authentication docs <https://docs.digitalasset.com/operate/3.4/howtos/secure/apis/jwt.html>`_
2. Ensure you can access the participant's ports 5001 and 5002
3. Add the configuration to a local file `console.conf`
    .. code-block::

        canton {
          remote-participants {
            participant {
              admin-api {
                port = 5002
                address = localhost
              }
              ledger-api {
                port = 5001
                address = localhost
              }
              token = "<auth token>"
            }
          }
          features.enable-preview-commands = yes
          features.enable-testing-commands = yes
          features.enable-repair-commands = yes
        }

4. Run the docker command

    .. parsed-literal::

        docker run -it --rm --network host -v $(pwd)/console.conf:/app/app.conf |docker_repo_prefix|/canton:|version_literal| --console

    .. important::
        If you run the participant using the docker compose setup the docker command must be run with the docker network used by the participant.
        Adjust the configuration to connect to the participant container:

            .. code-block::

                canton {
                  remote-participants {
                    participant {
                      admin-api {
                        port = 5002
                        address = participant
                      }
                      ledger-api {
                        port = 5001
                        address = participant
                      }
                      token = "<auth token>"
                    }
                  }
                  features.enable-preview-commands = yes
                  features.enable-testing-commands = yes
                  features.enable-repair-commands = yes
                }

        Running docker with the default network (`splice-validator`):

        .. parsed-literal::

            docker run -it --rm --network splice-validator -v $(pwd)/console.conf:/app/app.conf |docker_repo_prefix|/canton:|version_literal| --console

Sequencer console
+++++++++++++++++

1. Ensure you can access the sequencer's ports 5008 and 5009
2. Add the configuration to a local file `console.conf`

    .. code-block::

        canton {
          remote-sequencers {
            sequencer {
              public-api {
                port = 5008
                address = localhost
              }
              admin-api {
                port = 5009
                address = localhost
              }
            }
          }
          features.enable-preview-commands = yes
          features.enable-testing-commands = yes
          features.enable-repair-commands = yes
        }

3. Run the docker command

    .. parsed-literal::

        docker run -it --rm --network host -v $(pwd)/console.conf:/app/app.conf |docker_repo_prefix|/canton:|version_literal| --console

Mediator console
+++++++++++++++++

1. Ensure you can access the mediator's port 5007
2. Add the configuration to a local file `console.conf`

    .. code-block::

        canton {
          remote-mediators {
            mediator {
              admin-api {
                port = 5007
                address = localhost
              }
            }
          }
          features.enable-preview-commands = yes
          features.enable-testing-commands = yes
          features.enable-repair-commands = yes
        }

3. Run the docker command

    .. parsed-literal::

        docker run -it --rm --network host -v $(pwd)/console.conf:/app/app.conf |docker_repo_prefix|/canton:|version_literal| --console


Access in a K8s cluster
+++++++++++++++++++++++

In a K8s cluster you can use a debug pod to access the console directly from the cluster.

First you can create a pod running the right canton version using:

.. code-block:: bash

    kubectl debug "${POD_NAME}" --image "$(kubectl get pod "${POD_NAME}" -o json | jq -re '.spec.containers[0].image')" -i -t -- bash

where `POD_NAME` is the name of the participant/sequencer/mediator pod.

Once you are inside the running pod you can install a text editor and create the config file `console.conf` that is described above.

.. code-block:: bash

    $ apt-get update
    $ apt-get install -y vim
    $ vim console.conf # paste in the config from above
    $ /app/bin/canton -v -c console.conf
