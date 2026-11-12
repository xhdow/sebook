# sebook

> **Se**curity **Book** — a local, password-protected notebook that never puts
> your notes on someone else's server.

**sebook** is a small encrypted notebook for passwords, credentials and notes.
Everything lives in one file on your disk, encrypted with **AES-256-GCM** and a
key derived by **Argon2id**. There is no cloud, no sync server and no plaintext
ever written to disk unless you explicitly ask for it.

```
$ java -jar sebook.jar
== sebook :: encrypted notebook ==
type .help for commands.
/
> .set user tom
set user  [dirty]
  [val ] user = tom
> .save
```

- **One file, fully encrypted.** The whole notebook is a single opaque blob.
- **Nothing decrypted on disk.** Unlocking happens in memory; `.export` is the
  only way plaintext leaves the program, and it asks first.
- **Tamper-evident.** AES-GCM authenticates the data *and* the header, so
  nobody can silently downgrade the KDF parameters or edit your notes.
- **Mergeable.** Each value timestamps itself, so two copies edited on
  different machines can be merged without guessing which edit is newer.
- **No plaintext fingerprints.** The file has no magic bytes and no fixed
  prefix, so its first bytes reveal nothing about its format.

---

## Contents

- [Quick start](#quick-start)
- [Commands](#commands)
- [Merging notebooks](#merging-notebooks)
- [Data model](#data-model)
- [Encrypting arbitrary files](#encrypting-arbitrary-files)
- [Security notes](#security-notes)
- [Library use](#library-use)
- [Project layout](#project-layout)
- [Building](#building)

---

## Quick start

**Requirements:** JDK 17+. No Maven or Gradle; the third-party jars are vendored
in `lib/`.

```powershell
git clone <your-repo-url> sebook
cd sebook
.\build.ps1                 # compiles and produces dist\sebook.jar
java -jar dist\sebook.jar   # creates/opens .\sebook.vault
```

On the first run you are asked for a new password (twice) and an empty vault is
written. Afterwards you unlock with the same password. Use a different file by
passing its path:

```powershell
java -jar dist\sebook.jar my-notes.vault
```

Build flags:

```powershell
.\build.ps1                 # compile and package dist\sebook.jar
.\build.ps1 -Test           # also compile and run the self-check suite
.\build.ps1 -Clean          # remove build output first
```

## Commands

| Command | Description |
| --- | --- |
| `.cd <path\|name>` | change directory and list its contents; absolute (`/a/b`), relative (`a/b`), `..`, `.`; fuzzy names allowed; values cannot be entered |
| `.pwd` | print the current path |
| `.ls` | list subdirectories and values of the current node |
| `.ll` | like `.ls`, plus each node's own timestamp and untruncated values |
| `.set <key> <value>` | set a value, stored as text exactly as typed; the node is listed afterwards |
| `.rm <name> [--force]` | delete a value, or a directory and everything below it (asks first) |
| `.mkdir <name>` | create a subdirectory |
| `.save` | encrypt and write the vault (password entered twice and verified) |
| `.export <file>` | export **decrypted** JSON (asks for confirmation) |
| `.import <file>` | merge a plaintext notebook JSON into the current one |
| `.discard` | drop unsaved changes and reload from disk |
| `.pass <length>` | generate a random password (lowercase, uppercase, digits, symbols) |
| `.help` / `.exit` | |

Notes:

- Values with spaces must be quoted: `.set title "my long title"`.
- `.set` stores text verbatim, so `000888` stays `000888`.
- `.save` asks for the password twice; if the two entries differ, or the freshly
  written ciphertext cannot be decrypted again with it, nothing is written.
- Names are resolved with edit distance: `wrk` finds `work` when it is the only
  close match. When several children match, the candidates are listed and you
  must give an exact name.
- Timestamps are shown in the machine's local time zone with its UTC offset.

## Merging notebooks

`.import file.json` merges a plaintext notebook — for example one produced by
`.export` on another machine — into the open notebook, starting at the root:

- a path only in the imported file is added as-is;
- a path only in the current notebook is **always kept** (merging only ever
  adds, so notebooks used for different work keep all their branches; delete
  explicitly with `.rm`);
- a value present on both sides with different content: the newer one wins,
  compared by the value's **own** `ts`, so `user` and `password` under the same
  directory are decided independently;
- kept decisions change nothing, so kept nodes keep their timestamps.

A node whose content changed ends up with `min(max(imported ts, current ts), now)`,
so no timestamp can land in the future. The merge is all-or-nothing: the file
is parsed and merged into a copy first, so a malformed file leaves the notebook
untouched. Nothing is written until `.save`.

The full algorithm, as pseudo code with explanations, is in
[`docs/merge-algorithm.md`](docs/merge-algorithm.md).

## Data model

Every entry is a JSON object with its own numeric `ts` (epoch milliseconds). The
reserved key `nt` selects the kind:

- **directory** — `nt` is absent or `"d"`; its other keys are its children;
- **value** — `nt` is `"v"`; the payload lives in `val` and may be any JSON
  value (string, number, boolean, array, object, null).

```json
{
  "ts": 1794312289193,
  "nt": "d",
  "title": { "ts": 1794312289193, "nt": "v", "val": "root" },
  "work": {
    "ts": 1794312289193,
    "nt": "d",
    "deep": {
      "ts": 1794312289193,
      "nt": "d",
      "note": { "ts": 1794312289193, "nt": "v", "val": "hello" }
    }
  }
}
```

- Writing a value updates that value's `ts` **and every ancestor up to the
  root**; sibling values are untouched.
- Writing a value equal to the existing one is a no-op and changes no `ts`.
- Navigation is read-only and never touches `ts`.
- `ts`, `nt` and `val` are reserved and cannot be used as child names.
- Values carry their own timestamp, which is what makes merging unambiguous.

## Encrypting arbitrary files

`dist\filetool.cmd` encrypts and decrypts **any** file, not just notebooks:

```powershell
dist\filetool.cmd encrypt photo.jpg                 # -> photo.jpg.enc
dist\filetool.cmd encrypt data.bin out.vault        # explicit output
dist\filetool.cmd decrypt photo.jpg.enc             # -> photo.jpg
dist\filetool.cmd decrypt data.bin.enc restored.bin
dist\filetool.cmd info photo.jpg.enc                # header metadata + salt/nonce hex
```

- Output defaults to `<input>.enc` when encrypting; when decrypting, `.enc` is
  stripped, otherwise `.dec` is appended.
- Existing files are never overwritten unless `--force` is given.
- Passwords are always read interactively, never from the command line, so they
  do not end up in the process list or shell history.
- `info` needs no password and decrypts nothing. It reports the format version,
  KDF, KDF parameters, key/salt/nonce lengths, the salt and nonce in hex,
  padding length, payload size and total overhead.
- Files are read fully into memory and written atomically, so very large files
  need a correspondingly large heap (`java -Xmx...`).

## Security notes

- **KDF:** Argon2id, 64 MiB memory, 3 iterations, 1 lane (configurable through
  `Vault.encrypt(..., memoryKib, iterations, parallelism)`).
- **Cipher:** AES-256-GCM. The whole header — including salt and KDF parameters
  — is authenticated as associated data, so parameters cannot be downgraded.
- **No file signature.** The format has no magic bytes. Every file starts with a
  one-byte padding length followed by 0–255 random bytes, so files have no fixed
  prefix or size and cannot be fingerprinted by their first bytes. The remaining
  header fields are random (salt, nonce) or high-entropy values, so the whole
  file reads as a uniform random stream.
- **Bounded KDF parameters.** When reading a file, KDF parameters are
  range-checked (8 MiB–1 GiB, ≤ 1000 iterations) so a crafted header cannot
  exhaust memory.
- **Wrong password vs. tampering.** A wrong password and a tampered file are
  indistinguishable: both fail the GCM tag check.
- **No plaintext temp files.** `.save` writes through a sibling temp file that
  contains only ciphertext, then renames atomically.
- **Memory hygiene.** Key and plaintext buffers are zeroed after use; passwords
  are read with `Console.readPassword` and held as `char[]` that are wiped.

## Library use

`Vault` and `NodeTree` do not depend on each other or on the CLI, so both can be
embedded in other projects:

```java
// Crypto only
byte[] file  = Vault.encrypt(plaintextBytes, password);
byte[] plain = Vault.decrypt(file, password);

// Tree only
NodeTree t = new NodeTree();
t.mkdir("work");
t.cd("work");
t.set("title", JsonNodeFactory.instance.textNode("hello"));
String json = t.toJson();
```

`NodeTree` is not thread safe; serialize access externally.

## Project layout

```
src/main/java/sebook/
  crypto/   Vault              AES-256-GCM + Argon2id, standalone library
  tree/     NodeTree           in-memory JSON node tree, standalone library
  cli/      SebookCli          interactive shell
            FileToolCli        encrypt/decrypt any file, inspect header
src/test/java/sebook/
  SelfCheck.java               dependency-free test suite
docs/
  merge-algorithm.md           merge rules as pseudo code, in natural language
```

## Building

Requires JDK 17+ (`javac`/`jar` on `PATH`). The third-party jars are vendored in
`lib/`:

| jar | purpose |
| --- | --- |
| `jackson-databind`, `jackson-core`, `jackson-annotations` | JSON |
| `bcprov-jdk18on` | Argon2id |

The build output is `dist\sebook.jar` plus `dist\lib\` and `dist\filetool.cmd`.


## License
This project is licensed under AGPLv3. Third-party dependencies retain their original licenses.
