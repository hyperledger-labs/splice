# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Configuration file for the Sphinx documentation builder.
#
# This file only contains a selection of the most common options. For a full
# list see the documentation:
# https://www.sphinx-doc.org/en/master/usage/configuration.html

# -- Path setup --------------------------------------------------------------

# If extensions (or modules to document with autodoc) are in another directory,
# add these directories to sys.path here. If the directory is relative to the
# documentation root, use os.path.abspath to make it absolute, like shown here.
#
import os
import sys

# import sys
# sys.path.insert(0, os.path.abspath('.'))

import json
import yaml
from datetime import date
import re


# make_id from docutils removes leading digits from IDs, which makes it impossible to have permanent links to numbered
# headings. We prefix IDs which start with a digit with "id-" before passing it to make_id to make permanent links work.
#
# See https://docutils.sourceforge.io/docs/ref/rst/directives.html#identifier-normalization
def setup(app):
    import docutils.nodes
    make_id_orig = docutils.nodes.make_id

    def make_id(string):
        prefixed_string = "id-" + string if string and string[0].isdigit() else string
        return make_id_orig(prefixed_string)

    docutils.nodes.make_id = make_id


# -- Project information -----------------------------------------------------

project = "Splice"
copyright = f" {date.today().year}, Digital Asset"
author = "Digital Asset"

# -- General configuration ---------------------------------------------------

# We highlight code blocks by default as Scala, which is used in Canton
# scripts. Other languages need to be marked explicitly as part of a
# code-block directive.
highlight_language = "scala"

# Add any Sphinx extension module names here, as strings. They can be
# extensions coming with Sphinx (named 'sphinx.ext.*') or your custom
# ones.
extensions = [
    "sphinx.ext.todo",
    "sphinx_copybutton",
    # ^^ Adds a copy-to-clipboard button to code-blocks.
    # It does not (yet) work for `parsed-literal::` directives, which
    # we use to inject version strings.
    # See https://github.com/executablebooks/sphinx-copybutton/issues/68
    #
    # Note: adjusting the copybutton_selector does not work directly, as
    # parsed_literal blocks just output a single <pre> tag, which does not
    # interact the right way with how copy-buttons are placed.
    "sphinx_reredirects",
    "sphinxcontrib.openapi",
]

# -- Redirects -----------------------------------------------------------
redirects = {
    "sv_operator/bootstrap": "sv_helm.html",
    "sv_operator/onboarding": "sv_helm.html",
}


# Configure the sphinx copy-button plugin as per
# https://sphinx-copybutton.readthedocs.io/en/latest/use.html#strip-and-configure-input-prompts-for-code-cells
copybutton_prompt_text = "@ "

# Add any paths that contain templates here, relative to this directory.
templates_path = ["_templates"]

# List of patterns, relative to source directory, that match files and
# directories to ignore when looking for source files.
# This pattern also affects html_static_path and html_extra_path.
exclude_patterns = ["_build", "Thumbs.db", ".DS_Store", "common/**"]


# -- Options for HTML output -------------------------------------------------

# The theme to use for HTML and HTML Help pages.  See the documentation for
# a list of builtin themes.
#
html_theme = "sphinx_rtd_theme"

# Theme options are theme-specific and customize the look and feel of a theme
# further.  For a list of options available for each theme, see the
# documentation.
#
html_theme_options = {
    "navigation_depth": -1,
    "collapse_navigation": False,
    # 'analytics_id': 'UA-64532708-4'
}

# Adding "Edit Source" links
# See https://docs.readthedocs.com/platform/latest/guides/edit-source-links-sphinx.html
html_context = {
    "display_github": True,
    "github_user": "hyperledger-labs",
    "github_repo": "splice",
    "github_version": "main",
    "conf_py_path": "/docs/src/",
}

html_js_files = ["script.js"]

SPLICE_ROOT = os.getenv("SPLICE_ROOT")
with open(SPLICE_ROOT + "/nix/canton-sources.json") as f:
    obj = json.load(f)
    canton_version = obj["version"]
    daml_sdk_tooling_version = obj["tooling_sdk_version"]

with open(SPLICE_ROOT + "/daml.yaml") as f:
    obj = yaml.safe_load(f)
    daml_sdk_version = obj["sdk-version"]

with open(os.path.join(os.getenv("CANTON"), "SUBDIR")) as f:
    canton_subdir = f.readline()

version = os.getenv("VERSION")
if not version:
    print("Environment variable VERSION must be set")
    sys.exit(1)
chart_version = version

if re.match(r"^[0-9]+.[0-9]+.[0-9]+$", version):
    # For releases, we download artifacts from GitHub Releases
    download_url = f"https://github.com/digital-asset/decentralized-canton-sync/releases/download/v{version}"
    helm_repo_prefix = os.getenv("OCI_RELEASE_HELM_REGISTRY")
    docker_repo_prefix = os.getenv("RELEASE_DOCKER_REGISTRY")
else:
    # For snapshots, we download artifacts through the gcs proxy on the cluster
    download_url = "/cn-release-bundles"
    helm_repo_prefix = os.getenv("OCI_DEV_HELM_REGISTRY")
    docker_repo_prefix = os.getenv("DEV_DOCKER_REGISTRY")

# Sphinx does not allow something like ``|version|``
# so instead we define a replacement that includes the formatting.
rst_prolog = f"""
.. role:: raw-html(raw)
   :format: html

.. |splice_cluster| replace:: :raw-html:`<span class="splice-cluster">unknown_cluster</span>`

.. |da_hostname| replace:: :raw-html:`<span class="splice-url-prefix">unknown_cluster.</span>global.canton.network.digitalasset.com`
.. |gsf_sv_url| replace:: :raw-html:`https://sv.sv-1.<span class="splice-url-prefix">unknown_cluster.</span>global.canton.network.sync.global`
.. |generic_sv_url| replace:: :raw-html:`https://sv.sv-1.<span class="splice-url-prefix">unknown_cluster.</span>global.canton.network.YOUR_SV_SPONSOR`
.. |gsf_scan_url| replace:: :raw-html:`https://scan.sv-1.<span class="splice-url-prefix">unknown_cluster.</span>global.canton.network.sync.global`
.. |generic_scan_url| replace:: :raw-html:`https://scan.sv-1.<span class="splice-url-prefix">unknown_cluster.</span>global.canton.network.YOUR_SV_SPONSOR`
.. |gsf_sequencer_url| replace:: :raw-html:`https://sequencer-MIGRATION_ID.sv-1.<span class="splice-url-prefix">unknown_cluster.</span>global.canton.network.sync.global`

.. |version_literal| replace:: ``{version}``
.. |chart_version_literal| replace:: ``{chart_version}``
.. |canton_version| replace:: {canton_version}
.. |canton_subdir| replace:: {canton_subdir}
.. |daml_sdk_tooling_version| replace:: {daml_sdk_tooling_version}
.. |daml_sdk_version| replace:: {daml_sdk_version}

.. |chart_version_set| replace:: ``export CHART_VERSION={chart_version}``
.. |image_tag_set| replace:: ``export IMAGE_TAG={version}``
.. |image_tag_set_plain| replace:: export IMAGE_TAG={version}

.. |bundle_download_link| replace:: :raw-html:`<a class="reference external" href="{download_url}/{version}_splice-node.tar.gz">Download Bundle</a>`
.. |openapi_download_link| replace:: :raw-html:`<a class="reference external" href="{download_url}/{version}_openapi.tar.gz">Download OpenAPI specs</a>`

.. |helm_repo_prefix| replace:: {helm_repo_prefix}
.. |docker_repo_prefix| replace:: {docker_repo_prefix}
"""
