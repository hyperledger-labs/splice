import { expect, jest, test } from '@jest/globals';
import dedent from 'dedent';
import { readFileSync } from 'fs';

import { readAndParseYaml } from './configLoader';

jest.mock('fs');
jest.mock('path', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const original = jest.requireActual('path') as any;
  return {
    ...original,
    resolve: (...paths: Array<string>) => {
      return original.resolve('/test', ...paths);
    },
  };
});

test('config loader loads a simple config without inclusions', () => {
  mockFiles({
    '/test/a.yaml': 'test: Hello, world!',
  });
  expect(readAndParseYaml('a.yaml')).toEqual({ test: 'Hello, world!' });
});

test('a simple inclusion includes the whole mapping', () => {
  mockFiles({
    '/test/included.yaml': dedent`
      section:
        prop0: 'default'
        prop1: 0
    `,
    '/test/including.yaml': dedent`
      !include(./included.yaml)
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({
    section: {
      prop0: 'default',
      prop1: 0,
    },
  });
});

test('an inclusion with overrides preserves values that are not overridden and overrides values that are', () => {
  mockFiles({
    '/test/included.yaml': dedent`
      section:
        prop0: 'default'
        prop1: 0
    `,
    '/test/including.yaml': dedent`
      !include(./included.yaml)
      section:
        prop1: 42
        prop2: 'new'
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({
    section: {
      prop0: 'default',
      prop1: 42,
      prop2: 'new',
    },
  });
});

test('inclusions can be done under a nested property', () => {
  mockFiles({
    '/test/included.yaml': dedent`
      prop0: 'default'
      prop1: 0
    `,
    '/test/including.yaml': dedent`
      original: test
      included: !include(./included.yaml)
        prop1: 42
        prop2: 'new'
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({
    original: 'test',
    included: {
      prop0: 'default',
      prop1: 42,
      prop2: 'new',
    },
  });
});

test('inclusions can be nested in overrides', () => {
  mockFiles({
    '/test/including.yaml': dedent`
      !include(included1.yaml)
      section:
        prop0: 2
        prop1: !include(included2.yaml)
    `,
    '/test/included1.yaml': dedent`
      section:
        prop0: 0
        prop1:
          value 1
    `,
    '/test/included2.yaml': dedent`
      value: 3
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({
    section: {
      prop0: 2,
      prop1: { value: 3 },
    },
  });
});

test('when including from multiple paths the latter take precedence', () => {
  mockFiles({
    '/test/included1.yaml': dedent`
      a: 1
      b: 2
      c: 3
    `,
    '/test/included2.yaml': dedent`
      b: 4
      c: 5
    `,
    '/test/included3.yaml': dedent`
      c: 6
    `,
    '/test/including.yaml': dedent`
      !include(included1.yaml;included2.yaml;included3.yaml)
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({
    a: 1,
    b: 4,
    c: 6,
  });
});

test('inclusions are resolved recursively', () => {
  mockFiles({
    '/test/including.yaml': dedent`
      !include(included.yaml)
    `,
    '/test/included.yaml': dedent`
      !include(included-recursively.yaml)
    `,
    '/test/included-recursively.yaml': dedent`
      test: value
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({ test: 'value' });
});

test('included paths are resolved relative to the including file', () => {
  mockFiles({
    '/test/including.yaml': dedent`
      !include(common/included.yaml)
    `,
    '/test/common/included.yaml': dedent`
      !include(included-recursively.yaml)
    `,
    '/test/common/included-recursively.yaml': dedent`
      test: value
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({ test: 'value' });
});

test('a top level sequence can be included', () => {
  mockFiles({
    '/test/including.yaml': dedent`
      sequence: !include(included.yaml)
    `,
    '/test/included.yaml': dedent`
    - 1
    - 2
    - 3
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({ sequence: [1, 2, 3] });
});

test('a top level scalar can be included', () => {
  mockFiles({
    '/test/including.yaml': dedent`
      scalar: !include(included.yaml)
    `,
    '/test/included.yaml': dedent`
    scalar value
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({ scalar: 'scalar value' });
});

test('overriding sequences replaces the old sequence with the new one', () => {
  mockFiles({
    '/test/included.yaml': dedent`
      sequence:
        - 1
        - 2
        - 3
    `,
    '/test/including.yaml': dedent`
      !include(./included.yaml)
      sequence:
        - 4
        - 5
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({ sequence: [4, 5] });
});

test('overriding sequences with the !append tag appends new sequence to the old one', () => {
  mockFiles({
    '/test/included.yaml': dedent`
      sequence:
        - 1
        - 2
        - 3
    `,
    '/test/including.yaml': dedent`
      !include(./included.yaml)
      sequence: !append
        - 4
        - 5
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({ sequence: [1, 2, 3, 4, 5] });
});

test('empty sequences can be appended to', () => {
  mockFiles({
    '/test/included.yaml': dedent`
      empty-seq: []
      empty-mapping:
    `,
    '/test/including.yaml': dedent`
      !include(./included.yaml)
      empty-seq: !append
        - item
      empty-mapping: !append
        - item
      new-seq: !append
        - item
    `,
  });
  expect(readAndParseYaml('including.yaml')).toEqual({
    'empty-seq': ['item'],
    'empty-mapping': ['item'],
    'new-seq': ['item'],
  });
});

test('!append cannot be used with types other than a sequence', () => {
  mockFiles({
    '/test/null.yaml': dedent`
      null: !append
    `,
    '/test/scalar.yaml': dedent`
      scalar: !append test
    `,
    '/test/mapping.yaml': dedent`
      mapping: !append
        prop: test
    `,
  });
  expect(() => readAndParseYaml('null.yaml')).toThrow();
  expect(() => readAndParseYaml('scalar.yaml')).toThrow();
  expect(() => readAndParseYaml('mapping.yaml')).toThrow();
});

test('each file is read only once, even when it is included multiple times through different relative paths', () => {
  mockFiles({
    '/test/main.yaml': dedent`
      !include(common/a.yaml;./common/b.yaml)
      a: !include(../test/common/a.yaml)
      b: !include(common/../common/b.yaml)
    `,
    '/test/common/a.yaml': dedent`
      !include(b.yaml)
    `,
    '/test/common/b.yaml': dedent`
      test
    `,
  });
  expect(readAndParseYaml('main.yaml')).toEqual({
    a: 'test',
    b: 'test',
  });
  expect(readFileSync).toHaveBeenCalledTimes(3);
  expect(readFileSync).toHaveBeenCalledWith('/test/main.yaml', 'utf-8');
  expect(readFileSync).toHaveBeenCalledWith('/test/common/a.yaml', 'utf-8');
  expect(readFileSync).toHaveBeenCalledWith('/test/common/b.yaml', 'utf-8');
});

test('detect cyclic dependency in a single file', () => {
  mockFiles({
    '/test/self.yaml': dedent`
      !include(self.yaml)
    `,
  });
  expect(() => readAndParseYaml('self.yaml')).toThrow(
    'Cyclic dependency detected: [/test/self.yaml -> /test/self.yaml].'
  );
});

test('detect cyclic dependency between multiple files', () => {
  mockFiles({
    '/test/a.yaml': dedent`
      !include(b.yaml)
    `,
    '/test/b.yaml': dedent`
      !include(c.yaml)
    `,
    '/test/c.yaml': dedent`
      !include(a.yaml)
    `,
  });
  expect(() => readAndParseYaml('a.yaml')).toThrow(
    'Cyclic dependency detected: [/test/a.yaml -> /test/b.yaml -> /test/c.yaml -> /test/a.yaml].'
  );
});

function mockFiles(contentsByPath: Partial<Record<string, string>>) {
  (readFileSync as jest.Mock).mockReset().mockImplementation(path => {
    const contents = typeof path === 'string' ? contentsByPath[path] : undefined;
    if (contents === undefined) {
      throw new Error(`file [${path}] is not mocekd`);
    }
    return contents;
  });
}
