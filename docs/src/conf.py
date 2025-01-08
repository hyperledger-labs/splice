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
    "sphinx_reredirects",
    # ^^ Adds a copy-to-clipboard button to code-blocks.
    # It does not (yet) work for `parsed-literal::` directives, which
    # we use to inject version strings.
    # See https://github.com/executablebooks/sphinx-copybutton/issues/68
    #
    # Note: adjusting the copybutton_selector does not work directly, as
    # parsed_literal blocks just output a single <pre> tag, which does not
    # interact the right way with how copy-buttons are placed.
]

# -- Redirects -----------------------------------------------------------
redirects = {
    "sv_operator/bootstrap": "sv_helm.html",
    "sv_operator/onboarding": "sv_helm.html",
}


todo_include_todos = True


# Configure the sphinx copy-button plugin as per
# https://sphinx-copybutton.readthedocs.io/en/latest/use.html#strip-and-configure-input-prompts-for-code-cells
copybutton_prompt_text = "@ "

# Add any paths that contain templates here, relative to this directory.
templates_path = ["_templates"]

# List of patterns, relative to source directory, that match files and
# directories to ignore when looking for source files.
# This pattern also affects html_static_path and html_extra_path.
exclude_patterns = ["_build", "Thumbs.db", ".DS_Store"]


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


html_js_files = ["script.js"]

repo_root = os.getenv("REPO_ROOT")
with open(repo_root + "/nix/canton-sources.json") as f:
    obj = json.load(f)
    canton_version = obj["version"]
    daml_sdk_tooling_version = obj["tooling_sdk_version"]

with open(repo_root + "/daml.yaml") as f:
    obj = yaml.safe_load(f)
    daml_sdk_version = obj["sdk-version"]

with open(os.path.join(os.getenv("CANTON"), "SUBDIR")) as f:
    canton_subdir = f.readline()

version = os.getenv("VERSION")
if not version:
    print("Environment variable VERSION must be set")
    sys.exit(1)
chart_version = version

# Sphinx does not allow something like ``|version|``
# so instead we define a replacement that includes the formatting.
rst_prolog = f"""
.. role:: raw-html(raw)
   :format: html

.. |splice_cluster| replace:: :raw-html:`<span class="splice-cluster">unknown_cluster</span>`

.. |da_hostname| replace:: :raw-html:`<span class="splice-url-prefix">unknown_cluster</span>global.canton.network.digitalasset.com`
.. |gsf_sv_url| replace:: :raw-html:`https://sv.sv-1.<span class="splice-url-prefix">unknown_cluster</span>global.canton.network.sync.global`

.. |version_literal| replace:: ``{version}``
.. |chart_version_literal| replace:: ``{chart_version}``
.. |canton_version| replace:: {canton_version}
.. |canton_subdir| replace:: {canton_subdir}
.. |daml_sdk_tooling_version| replace:: {daml_sdk_tooling_version}
.. |daml_sdk_version| replace:: {daml_sdk_version}

.. |chart_version_set| replace:: ``export CHART_VERSION={chart_version}``
.. |image_tag_set| replace:: ``export IMAGE_TAG={version}``

.. |bundle_download_link| replace:: :raw-html:`<a class="reference external" href="/cn-release-bundles/{version}_splice-node.tar.gz">Download Bundle</a>`
.. |openapi_download_link| replace:: :raw-html:`<a class="reference external" href="/cn-release-bundles/{version}_openapi.tar.gz">Download OpenAPI specs</a>`

.. |canton_download_link| replace:: :raw-html:`<a class="reference external" href="https://digitalasset.jfrog.io/artifactory/canton-enterprise/canton-enterprise-{canton_version}.tar.gz">Download Canton enterprise</a>`
"""
