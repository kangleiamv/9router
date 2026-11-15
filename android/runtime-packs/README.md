# Bundled hosting runtimes (arm64-v8a)

`app/build.gradle.kts` adds this directory as a `jniLibs` source set, so Gradle
packages every file here as `lib/arm64-v8a/<name>` and the installer extracts it
to `nativeLibraryDir`. Android 10+ only executes binaries from that directory.

The files come from the Termux `aarch64` packages and are used unmodified except
for the renames below. `DT_RUNPATH` is `$ORIGIN`.

| file | runtime | version | sha256 |
| --- | --- | --- | --- |
| `libphp.so` | PHP CLI | 8.5.12 | `cb908e520160b60708f0633205b29f282788a723e4f040eea7b9693f236f8cbb` |
| `libnode.so` | Node.js | 26.13.1 | `11d9dafe2a9d986b6d88189d21b79fcba348f5f5b75c55691a841bee37340a0d` |
| `libmariadbd.so` | MariaDB server | 13.0.2 | `752304d7b27ad829ada9b93011fb2ad9c4ce575b99fd3347d6468b51a4d4e36b` |
| `libpg.so` | PostgreSQL server | 18.6 | `38b9cf302c46b5a9d38efbff5c75f8746a6de3b052d7b8215f3fba5777a2cb5b` |
| `libinitdb.so` | PostgreSQL `initdb` | 18.6 | `22a303f953fe38ef5a4ea743b2418f5a79a0d54ed27cfb6be0fd7ba9af8f41a0` |

Every other library in this directory is a dependency of those four, taken from
the same Termux packages.

## Renames

The APK packager only accepts native libraries whose file name ends in `.so`, so
versioned Termux libraries are renamed with a `9r` infix. The infix also keeps
them from shadowing the Android system copies:

| Termux name | bundled name |
| --- | --- |
| `libbz2.so.1.0` | `libbz29r.so` |
| `libcrypto.so.3` | `libcrypto9r.so` |
| `libicudata.so.79` | `libicudata9r.so` |
| `libicui18n.so.79` | `libicui18n9r.so` |
| `libicuio.so.79` | `libicuio9r.so` |
| `libicuuc.so.79` | `libicuuc9r.so` |
| `liblzma.so.5` | `liblzma9r.so` |
| `libncursesw.so.6` | `libncursesw9r.so` |
| `libreadline.so.8` | `libreadline9r.so` |
| `libssl.so.3` | `libssl9r.so` |
| `libxml2.so.16` | `libxml29r.so` |
| `libz.so.1` | `libz9r.so` |
| `libzstd.so.1` | `libzstd9r.so` |

Each rename uses `patchelf --replace-needed` and `--set-soname` on every bundled
file, so no `DT_NEEDED` entry references the old name.

`initdb` starts `postgres` as a sibling executable, so the server is shipped as
`libpg.so` and the `postgres` string inside `libinitdb.so` is patched to
`libpg.so`. PostgreSQL is compiled with `/data/data/ai.ninerouter/hosted` as its
prefix; the matching `share` and `lib` trees live in
`app/src/main/assets/runtimes/postgres` and are extracted there on first start.
MariaDB's `share` tree (system tables, error messages, charsets) is extracted
from `app/src/main/assets/runtimes/mariadb` at the same time.

## Licenses

- PHP — PHP License 3.01, <https://www.php.net/license/3_01.txt>
- Node.js — MIT, <https://github.com/nodejs/node/blob/main/LICENSE>
- MariaDB — GNU GPL v2, <https://mariadb.com/kb/en/mariadb-license/>
- PostgreSQL — PostgreSQL License, <https://www.postgresql.org/about/licence/>

Source packages: <https://github.com/termux/termux-packages>.

## Rebuilding

The binaries are not rebuilt by this repository. To refresh them, install the
Termux packages for `php`, `nodejs`, `mariadb` and `postgresql`, copy the
executables and their `readelf -d` dependencies here, apply the renames above,
then run the unit tests and `:app:assembleDebug`.
