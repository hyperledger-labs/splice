// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// This is a copy of https://github.com/TanStack/query/blob/118801df342881021c2bb6c8add5bb11b6dd944a/packages/query-core/src/utils.ts#L314 but extended to handle
// 1. OpenAPI generated classes which behave like plain objects for most intents and purposes but don't satisfy the default check
// 2. BigNumber which isn't a plain object but has clean equality check
// 3. Date which also isn't a plain object but has a clean equality check
// 4. Daml maps which also don't classify as a plain object
// The code is kept close to the original source to make the differences more obvious so we disable some checks like no-explicit-any rather than
// changing the source.
import BigNumber from 'bignumber.js';

/*eslint no-prototype-builtins: "off"*/

function isPlainArray(value: unknown) {
  return Array.isArray(value) && value.length === Object.keys(value).length;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function isOpenApiObject(o: any): boolean {
  if (!hasObjectPrototype(o)) {
    return false;
  }

  // If has modified constructor
  const ctor = o.constructor;
  if (typeof ctor === 'undefined') {
    return true;
  }

  // If has modified prototype
  const prot = ctor.prototype;
  if (!hasObjectPrototype(prot)) {
    return false;
  }

  const keys = Object.keys(Object.getPrototypeOf(o).constructor).sort();
  const expected = ['getAttributeTypeMap', 'discriminator', 'attributeTypeMap'].sort();
  return keys.length === expected.length && keys.every((val, index) => val === expected[index]);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function isPlainObject(o: any): o is object {
  if (!hasObjectPrototype(o)) {
    return false;
  }

  // If has modified constructor
  const ctor = o.constructor;
  if (typeof ctor === 'undefined') {
    return true;
  }

  // If has modified prototype
  const prot = ctor.prototype;
  if (!hasObjectPrototype(prot)) {
    return false;
  }

  // If constructor does not have an Object-specific method
  if (!prot.hasOwnProperty('isPrototypeOf')) {
    return false;
  }

  // Most likely a plain Object
  return true;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function hasObjectPrototype(o: any): boolean {
  return Object.prototype.toString.call(o) === '[object Object]';
}

export function replaceEqualDeep<T>(a: unknown, b: T): T;
// eslint-disable-next-line @typescript-eslint/explicit-module-boundary-types, @typescript-eslint/no-explicit-any
export function replaceEqualDeep(a: any, b: any): any {
  if (a === b) {
    return a;
  }

  if (a instanceof BigNumber && b instanceof BigNumber) {
    return a.eq(b) ? a : b;
  }

  if (a instanceof Date && b instanceof Date) {
    return a.getTime() === b.getTime() ? a : b;
  }

  const array = isPlainArray(a) && isPlainArray(b);
  const plainObject = isPlainObject(a) && isPlainObject(b);
  const openApiObject =
    isOpenApiObject(a) && isOpenApiObject(b) && Object.getPrototypeOf(a).isPrototypeOf(b);

  if (array || plainObject || openApiObject) {
    const aSize = array ? a.length : Object.keys(a).length;
    const bItems = array ? b : Object.keys(b);
    const bSize = bItems.length;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const copy: any = array
      ? []
      : isOpenApiObject(a)
        ? Object.create(Object.getPrototypeOf(a))
        : {};

    let equalItems = 0;

    for (let i = 0; i < bSize; i++) {
      const key = array ? i : bItems[i];
      copy[key] = replaceEqualDeep(a[key], b[key]);
      if (copy[key] === a[key]) {
        equalItems++;
      }
    }

    return aSize === bSize && equalItems === aSize ? a : copy;
  }

  return b;
}
