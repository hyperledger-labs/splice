import { RestHandler, rest } from 'msw';

export const buildJsonApiMock = (jsonApiUrl: string): RestHandler[] => [
  rest.post(`${jsonApiUrl}v1/user`, (_, res, ctx) => {
    return res(
      ctx.json({
        result: {
          primaryParty:
            'alice::122015ba7aa9054dbad217110e8fbf5dd550a59fb56df5986913f7b9a8e63bad8570',
          userId: 'alice',
        },
        status: 200,
      })
    );
  }),
];
