# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from docutils.parsers.rst import Directive
from docutils import nodes

# Automatically creates a release notes section with a download link to the release bundle

class ReleaseNotesDirective(Directive):
    # This directive expects one argument: the version number
    required_arguments = 1
    optional_arguments = 0
    final_argument_whitespace = True
    has_content = True

    def run(self):
        version = self.arguments[0].strip()

        section = nodes.section(
            '',
            nodes.title(text=f'{version}'),
            ids=[nodes.make_id(f'id-{version}')]
        )

        if version.lower() != 'upcoming':
          download_url = f"https://github.com/digital-asset/decentralized-canton-sync/releases/download/v{version}/{version}_splice-node.tar.gz"
          link_text = f'Download release bundle for version {version}'
          link_node = nodes.paragraph(
              '', '',
              nodes.reference('', link_text, refuri=download_url)
          )
          section += link_node

        if self.content:
            self.state.nested_parse(
                self.content,
                self.content_offset,
                section
            )

        return [section]

def setup(app):
    app.add_directive('release-notes', ReleaseNotesDirective)

    return {
        'version': '1.0',
        'parallel_read_safe': True,
        'parallel_write_safe': True,
    }
