export const vitest_common_conf = {
  test: {
    environment: 'happy-dom',
    typecheck: {
      include: ['**/*.{test,spec}-d.?(c|m)[jt]s?(x)', '**/*.{test,spec}.?(c|m)[jt]s?(x)'],
    },
    exclude: ['../lib/**'],
    silent: false,
    disableConsoleIntercept: true,
  },
};
