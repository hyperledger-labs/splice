export default {
  test: {
    environment: 'happy-dom',
    typecheck: {
      include: ['**/*.{test,spec}-d.?(c|m)[jt]s?(x)', '**/*.{test,spec}.?(c|m)[jt]s?(x)'],
    },
    exclude: ['../lib/**'],
    silent: true,
  },
};
