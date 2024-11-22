// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// @ts-ignore
import * as jsondiffpatch from 'jsondiffpatch';
// @ts-ignore
import * as htmlFormatter from 'jsondiffpatch/formatters/html';
import DiffMatchPatch from 'diff-match-patch';
import DOMPurify from 'dompurify';
import parse from 'html-react-parser';
import React from 'react';

import { Box } from '@mui/material';
import { GlobalStyles } from '@mui/system';

import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { DsoRulesConfig } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

const jsondiffpatchInstance = jsondiffpatch.create({
  arrays: {
    detectMove: true,
    includeValueOnMove: false,
  },
  textDiff: {
    diffMatchPatch: DiffMatchPatch,
    minLength: 60,
  },
  cloneDiffValues: true,
});

const JsonDiffStyles = () => (
  // original template: https://esm.sh/jsondiffpatch@0.6.0/lib/formatters/styles/html.css
  <GlobalStyles
    styles={{
      '.jsondiffpatch-delta': {
        fontFamily: "'Bitstream Vera Sans Mono', 'DejaVu Sans Mono', Monaco, Courier, monospace",
        fontSize: '12px',
        margin: 0,
        padding: '0 0 0 12px',
        display: 'inline-block',
      },
      '.jsondiffpatch-delta pre': {
        fontFamily: "'Bitstream Vera Sans Mono', 'DejaVu Sans Mono', Monaco, Courier, monospace",
        fontSize: '12px',
        margin: 0,
        padding: 0,
        display: 'inline-block',
      },
      'ul.jsondiffpatch-delta': {
        listStyleType: 'none',
        padding: '0 0 0 20px',
        margin: 0,
      },
      '.jsondiffpatch-delta ul': {
        listStyleType: 'none',
        padding: '0 0 0 20px',
        margin: 0,
      },
      '.jsondiffpatch-added .jsondiffpatch-property-name, .jsondiffpatch-added .jsondiffpatch-value pre, .jsondiffpatch-modified .jsondiffpatch-right-value pre, .jsondiffpatch-textdiff-added':
        {
          background: '#3cb505',
        },
      '.jsondiffpatch-deleted .jsondiffpatch-property-name, .jsondiffpatch-deleted pre, .jsondiffpatch-modified .jsondiffpatch-left-value pre, .jsondiffpatch-textdiff-deleted':
        {
          background: '#de1818',
          textDecoration: 'line-through',
        },
      '.jsondiffpatch-unchanged, .jsondiffpatch-movedestination': {
        color: 'gray',
      },
      '.jsondiffpatch-unchanged, .jsondiffpatch-movedestination > .jsondiffpatch-value': {
        transition: 'all 0.5s',
        overflowY: 'hidden',
      },
      '.jsondiffpatch-unchanged-showing .jsondiffpatch-unchanged, .jsondiffpatch-unchanged-showing .jsondiffpatch-movedestination > .jsondiffpatch-value':
        {
          maxHeight: '100px',
        },
      '.jsondiffpatch-unchanged-hidden .jsondiffpatch-unchanged, .jsondiffpatch-unchanged-hidden .jsondiffpatch-movedestination > .jsondiffpatch-value':
        {
          maxHeight: 0,
        },
      '.jsondiffpatch-unchanged-hiding .jsondiffpatch-movedestination > .jsondiffpatch-value, .jsondiffpatch-unchanged-hidden .jsondiffpatch-movedestination > .jsondiffpatch-value':
        {
          display: 'block',
        },
      '.jsondiffpatch-unchanged-visible .jsondiffpatch-unchanged, .jsondiffpatch-unchanged-visible .jsondiffpatch-movedestination > .jsondiffpatch-value':
        {
          maxHeight: '100px',
        },
      '.jsondiffpatch-unchanged-hiding .jsondiffpatch-unchanged, .jsondiffpatch-unchanged-hiding .jsondiffpatch-movedestination > .jsondiffpatch-value':
        {
          maxHeight: 0,
        },
      '.jsondiffpatch-unchanged-showing .jsondiffpatch-arrow, .jsondiffpatch-unchanged-hiding .jsondiffpatch-arrow':
        {
          display: 'none',
        },
      '.jsondiffpatch-value': {
        display: 'inline-block',
      },
      '.jsondiffpatch-property-name': {
        display: 'inline-block',
        paddingRight: '5px',
        verticalAlign: 'top',
      },
      '.jsondiffpatch-property-name:after': {
        content: '": "', // Correct usage of quotes
      },
      '.jsondiffpatch-child-node-type-array > .jsondiffpatch-property-name:after': {
        content: '": ["', // Correct usage of quotes
      },
      '.jsondiffpatch-child-node-type-array:after': {
        content: '"],"', // Correct usage of quotes
      },
      'div.jsondiffpatch-child-node-type-array:before': {
        content: '"["', // Correct usage of quotes
      },
      'div.jsondiffpatch-child-node-type-array:after': {
        content: '"]"', // Correct usage of quotes
      },
      '.jsondiffpatch-child-node-type-object > .jsondiffpatch-property-name:after': {
        content: '": {"', // Correct usage of quotes
      },
      '.jsondiffpatch-child-node-type-object:after': {
        content: '"}, "', // Correct usage of quotes
      },
      'div.jsondiffpatch-child-node-type-object:before': {
        content: '"{"', // Correct usage of quotes
      },
      'div.jsondiffpatch-child-node-type-object:after': {
        content: '"}"', // Correct usage of quotes
      },
      '.jsondiffpatch-value pre:after': {
        content: '","', // Correct usage of quotes
      },
      'li:last-child > .jsondiffpatch-value pre:after, .jsondiffpatch-modified > .jsondiffpatch-left-value pre:after':
        {
          content: '""', // Correct usage of quotes
        },
      '.jsondiffpatch-modified .jsondiffpatch-value': {
        display: 'inline-block',
      },
      '.jsondiffpatch-modified .jsondiffpatch-right-value': {
        marginLeft: '5px',
      },
      '.jsondiffpatch-moved .jsondiffpatch-value': {
        display: 'none',
      },
      '.jsondiffpatch-moved .jsondiffpatch-moved-destination': {
        display: 'inline-block',
        background: '#ffffbb',
        color: '#888',
      },
      '.jsondiffpatch-moved .jsondiffpatch-moved-destination:before': {
        content: '" => "', // Correct usage of quotes
      },
      'ul.jsondiffpatch-textdiff': {
        padding: 0,
      },
      '.jsondiffpatch-textdiff-location': {
        color: '#bbb',
        display: 'inline-block',
        minWidth: '60px',
      },
      '.jsondiffpatch-textdiff-line': {
        display: 'inline-block',
      },
      '.jsondiffpatch-textdiff-line-number:after': {
        content: '","', // Correct usage of quotes
      },
      '.jsondiffpatch-error': {
        background: 'red',
        color: 'white',
        fontWeight: 'bold',
      },
    }}
  />
);

interface PrettyJsonDiffProps {
  data?: DsoRulesConfig | AmuletConfig<USD>;
  compareWithData?: DsoRulesConfig | AmuletConfig<USD>;
}

export const PrettyJsonDiff: React.FC<PrettyJsonDiffProps> = ({ data, compareWithData }) => {
  // Calculate the difference between data objects
  const delta = jsondiffpatchInstance.diff(compareWithData, data);

  // If there's no difference, render the data as pretty-printed JSON
  if (!delta) {
    return (
      <Box
        component="pre"
        sx={{ overflow: 'auto', whiteSpace: 'pre-wrap', fontFamily: 'monospace' }}
        data-testid="stringify-display"
      >
        {JSON.stringify(data, null, 2)}
      </Box>
    );
  }

  // Sanitize and format the HTML for the diff 'display'
  // @ts-ignore
  const sanatizedHtml = DOMPurify.sanitize(htmlFormatter.format(delta, compareWithData));

  return (
    <>
      <JsonDiffStyles />
      <Box sx={{ overflow: 'auto' }}>
        <Box data-testid="config-diffs-display">{parse(sanatizedHtml)}</Box>
      </Box>
    </>
  );
};
