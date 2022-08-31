{ mkDerivation, aeson, aeson-pretty, ansi-terminal, base, binary
, blaze-html, bytestring, containers, cryptonite, Decimal, deepseq
, directory, either, extra, filepath, ghc, hashable, lens, lib
, megaparsec, memory, mtl, optparse-applicative, pretty
, proto3-suite, proto3-wire, recursion-schemes, safe-exceptions
, semver, template-haskell, text, time, unordered-containers
, utf8-string, vector, yaml, zip-archive
}:
mkDerivation {
  pname = "daml2ts";
  version = "1.0.0";
  src = ./vendored/daml2ts-1.0.0.tar.gz;
  isLibrary = false;
  isExecutable = true;
  executableHaskellDepends = [
    aeson aeson-pretty ansi-terminal base binary blaze-html bytestring
    containers cryptonite Decimal deepseq directory either extra
    filepath ghc hashable lens megaparsec memory mtl
    optparse-applicative pretty proto3-suite proto3-wire
    recursion-schemes safe-exceptions semver template-haskell text time
    unordered-containers utf8-string vector yaml zip-archive
  ];
  license = "unknown";
  hydraPlatforms = lib.platforms.none;
}
