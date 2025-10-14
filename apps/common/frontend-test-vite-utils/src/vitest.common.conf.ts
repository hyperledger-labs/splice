export const vitest_common_conf = {
  test: {
    disableConsoleIntercept: true,
    environment: 'happy-dom',
    exclude: ['../lib/**'],
    silent: false,
    testTimeout: 15000,
    typecheck: {
      include: ['**/*.{test,spec}-d.?(c|m)[jt]s?(x)', '**/*.{test,spec}.?(c|m)[jt]s?(x)'],
    },
  },
};
