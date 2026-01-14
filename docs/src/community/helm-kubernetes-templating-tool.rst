..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _helm_kubernetes_templating_tool:

Automated Helm/Kubernetes Templating Tool
=========================================

.. warning::

   This section features solutions shared by community members. 
   While they haven’t been formally tested by the Splice maintainers, 
   users are encouraged to verify the information independently. 
   Contributions to enhance accuracy and completeness are always welcome.

Managing deployments for Validator and Super Validator nodes using Kubernetes, Helm, and Git can be challenging,
especially when keeping multiple environments (DevNet, TestNet, MainNet) in sync with evolving configuration files and values.
Frequent version bumps, new variables, and hard migrations often require manual, error-prone updates to numerous ``values-*.yml`` files.
This manual process can lead to inconsistencies, missed configuration changes, and increased operational overhead.
The introduced solution provides a templating tool designed to automate and simplify the management of Helm values and environment-specific configurations.
This tool is intended as a supplemental resource for users seeking additional automation and templating flexibility alongside the official deployment guides.
To learn more about officially supported Kubernetes-based deployments, refer to :ref:`Kubernetes-Based Deployment of a Validator node <k8s_validator>` and :ref:`Kubernetes-Based Deployment of a Super Validator node <sv-helm>`.

Thanks to Stéphane Loeuillet for sharing this solution in a community discussion.
For more details and future updates, see the `kaikodata/canton-tooling <https://github.com/kaikodata/canton-tooling/blob/master/kubernetes/README.md#canton-templating-script>`_ repository.

Key Features of the Solution
----------------------------

- Automated templating of YAML configurations from source directories and environment variable files, including substitution of placeholders in both file content and filenames, minimizing manual edits and reducing risk of missed updates.
- Multi-environment support for DevNet, TestNet, and MainNet, handling different chart versions, migration IDs, OIDC parameters, and other environment-specific values for seamless deployment across networks.
- Symlink creation for directories renamed due to variable substitution, with automatic cleanup of existing symlinks to prevent nesting and ensure clean processing on repeated runs.
- CLI options for debug logging for string substitutions (including support for #-containing variables), alias prefixing and YAML reindentation based on Chart.yaml dependencies, and .gitignore management (either per output directory or at the repository root).
- Consistent output of "legal" YAML files compatible with CI tools such as yamllint.
- Shell scripts provided for batch updating all YAML files with new releases, streamlining the upgrades and reducing operational overhead.
- Open for community contributions and suggestions, with a focus on improving automation and upstream example values files to benefit all users.
- Planned extensibility for secrets and ingress templates, supporting advanced deployment scenarios and future enhancements for Kubernetes environments (in progress as of May 2025).
