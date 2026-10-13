// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { GlobalStyles, ThemeProvider } from '@mui/material';
import { PartyId, theme } from '@canton-network/splice-common-frontend';
import { describe, expect, test } from 'vitest';

import CopyableIdentifier from '../../components/ui/CopyableIdentifier';
import MemberIdentifier from '../../components/ui/MemberIdentifier';
import { partyIdScrollGlobalStyles } from '../../components/ui/identifierStyles';
import PartyIdScrollTracks from '../../components/PartyIdScrollTracks';

const LONG_CONTRACT_ID =
  '00deadbeefcafebabe0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

const LONG_PARTY_ID = `digital-asset-2::12200eab17c2b87a3da9f7b3b81d371ff794a4515fa3a0b258422a251d6148b031d`;

const NarrowContainer: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div style={{ width: 120 }}>{children}</div>
);

describe('CopyableIdentifier', () => {
  test('renders the full contract ID for horizontal scrolling', () => {
    render(<CopyableIdentifier value={LONG_CONTRACT_ID} size="small" data-testid="contract-id" />);

    expect(screen.getByTestId('contract-id-value')).toHaveTextContent(LONG_CONTRACT_ID);
    expect(screen.getByTestId('contract-id-scroll')).toHaveStyle({ overflowX: 'auto' });
  });

  test('keeps the copy button adjacent to short identifiers', () => {
    render(
      <div style={{ width: 800 }}>
        <CopyableIdentifier value="Digital-Asset-Eng-2" size="large" data-testid="short-id" />
      </div>
    );

    expect(screen.getByTestId('short-id')).toHaveStyle({
      display: 'inline-flex',
      width: 'fit-content',
    });
  });

  test('compact scroll keeps #1785 scrolling at the Figma width', async () => {
    render(
      <NarrowContainer>
        <CopyableIdentifier
          value={LONG_CONTRACT_ID}
          size="large"
          maxWidth={270}
          data-testid="contract-id"
        />
      </NarrowContainer>
    );

    const scroll = screen.getByTestId('contract-id-scroll');
    expect(scroll).toHaveStyle({ overflowX: 'auto', maxWidth: '270px' });
    expect(screen.getByTestId('contract-id-value')).toHaveTextContent(LONG_CONTRACT_ID);

    Object.defineProperty(scroll, 'scrollWidth', { configurable: true, value: 400 });
    Object.defineProperty(scroll, 'clientWidth', { configurable: true, value: 100 });
    Object.defineProperty(scroll, 'scrollLeft', { configurable: true, value: 0 });
    fireEvent.scroll(scroll);

    await waitFor(() => {
      expect(screen.queryByTestId('contract-id-ellipsis-cue')).not.toBeInTheDocument();
      expect(screen.getByTestId('contract-id-scroll-track')).toBeInTheDocument();
    });
  });

  test('fullWidth fills the parent and keeps scrolling', () => {
    render(
      <div style={{ width: 200 }}>
        <CopyableIdentifier
          value={LONG_CONTRACT_ID}
          size="large"
          fullWidth
          data-testid="contract-id"
        />
      </div>
    );

    expect(screen.getByTestId('contract-id')).toHaveStyle({ width: '100%', display: 'flex' });
    expect(screen.getByTestId('contract-id-scroll')).toHaveStyle({ overflowX: 'auto' });
    expect(screen.getByTestId('contract-id-value')).toHaveTextContent(LONG_CONTRACT_ID);
  });

  test('trims long identifiers to the Figma ellipsis width without a narrow parent', () => {
    render(
      <CopyableIdentifier
        value={LONG_CONTRACT_ID}
        size="large"
        overflow="ellipsis"
        data-testid="contract-id"
      />
    );

    expect(screen.getByTestId('contract-id-value')).toHaveTextContent(LONG_CONTRACT_ID);
    expect(screen.getByTestId('contract-id-value')).toHaveAttribute('title', LONG_CONTRACT_ID);
    expect(screen.getByTestId('contract-id-ellipsis')).toHaveStyle({
      overflow: 'hidden',
      maxWidth: '270px',
    });
    expect(screen.getByTestId('contract-id-value')).toHaveStyle({ textOverflow: 'ellipsis' });
  });
});

describe('MemberIdentifier', () => {
  test('renders the full party ID instead of an abbreviated preview', () => {
    render(
      <MemberIdentifier partyId={LONG_PARTY_ID} isYou={false} size="large" data-testid="member" />
    );

    expect(screen.getByTestId('member-value')).toHaveTextContent(LONG_PARTY_ID);
    expect(screen.getByTestId('member-value')).not.toHaveTextContent('...');
    expect(screen.getByTestId('member-scroll')).toHaveStyle({ overflowX: 'auto' });
  });

  test('supports ellipsis overflow for compact layouts', () => {
    render(
      <NarrowContainer>
        <MemberIdentifier
          partyId={LONG_PARTY_ID}
          isYou={false}
          size="large"
          overflow="ellipsis"
          data-testid="member"
        />
      </NarrowContainer>
    );

    expect(screen.getByTestId('member-value')).toHaveTextContent(LONG_PARTY_ID);
    expect(screen.getByTestId('member-value')).toHaveAttribute('title', LONG_PARTY_ID);
    expect(screen.getByTestId('member-ellipsis')).toHaveStyle({ overflow: 'hidden' });
    expect(screen.getByTestId('member-value')).toHaveStyle({ textOverflow: 'ellipsis' });
    expect(screen.queryByTestId('member-scroll')).not.toBeInTheDocument();
  });
});

describe('common PartyId', () => {
  test('does not ellipsize the party ID when SV scroll styles are applied', async () => {
    render(
      <ThemeProvider theme={theme}>
        <GlobalStyles styles={partyIdScrollGlobalStyles} />
        <PartyIdScrollTracks />
        <NarrowContainer>
          <PartyId partyId={LONG_PARTY_ID} id="sv-party-id" />
        </NarrowContainer>
      </ThemeProvider>
    );

    const input = await screen.findByTestId('sv-party-id-input');
    const partyIdRoot = input.closest('.party-id');

    expect(input).toHaveDisplayValue(LONG_PARTY_ID);
    expect(input).toHaveStyle({ textOverflow: 'clip' });
    expect(partyIdRoot).toHaveClass('identifier-scroll-area');
    expect(partyIdRoot?.querySelector('.party-id-scroll-track')).not.toBeNull();
  });
});
