import { ErrorResponse, LookupEntryByPartyResponse } from 'directory-openapi';
import { RestHandler, rest } from 'msw';

export const buildDirectoryMock = (directoryUrl: string): RestHandler[] => [
  rest.get<null, { partyId: string }, LookupEntryByPartyResponse | ErrorResponse>(
    `${directoryUrl}/entries/by-party/:partyId`,
    (_, res, ctx) => {
      return res(
        ctx.status(404),
        ctx.json({
          error:
            'No directory entry found for party: alice::122015ba7aa9054dbad217110e8fbf5dd550a59fb56df5986913f7b9a8e63bad8570',
        })
      );
    }
  ),
];
