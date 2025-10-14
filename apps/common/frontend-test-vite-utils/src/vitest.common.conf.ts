export const vitest_common_conf = {
  test: {
    disableConsoleIntercept: true,
    environment: 'happy-dom',
    exclude: ['../lib/**'],
    include: ['**/*.{test,spec}-d.?(c|m)[jt]s?(x)', '**/*.{test,spec}.?(c|m)[jt]s?(x)'],
    silent: false,
    testTimeout: 15000,
    typecheck: {},
  },
};
