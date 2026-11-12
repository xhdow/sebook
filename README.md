# Encrypted Notebook

A local, password-protected personal notebook. The data is a tree of JSON
nodes, encrypted as a single **AES-256-GCM** file whose key is derived with
**Argon2id** (memory-hard, resistant to GPU cracking). Decryption always
happens in memory; plaintext only reaches the disk through an explicit
`.export`.

```
src/main/java/notebook/
  crypto/   Vault      AES-256-GCM + Argon2id, standalone library
  tree/     NodeTree   in-memory JSON node tree, standalone library
  cli/      NotebookCli  interactive shell
            FileToolCli  encrypt/decrypt any file, inspect header
src/test/java/notebook/
  SelfCheck.java       dependency-free test suite
docs/
  merge-algorithm.md   merge rules as pseudo code, in natural language
```

`Vault` and `NodeTree` do not depend on each other or on the CLI, so both can
be embedded in other projects.

## Build

Requires JDK 17+ (`javac`/`jar` on `PATH`). The third-party jars are already in
`lib/`:

| jar | purpose |
| --- | --- |
| `jackson-databind`, `jackson-core`, `jackson-annotations` | JSON |
| `bcprov-jdk18on` | Argon2id |

```powershell
.\build.ps1           # compile and package dist\notebook.jar
.\build.ps1 -Test     # also run the self-check suite
```

## Run

```powershell
java -jar dist\notebook.jar                 # uses .\notebook.vault
java -jar dist\notebook.jar my-notes.vault
```

On first run you are asked for a new password (twice) and an empty vault is
written. Afterwards you unlock with the same password.

## Commands

| command | description |
| --- | --- |
| `.cd <path\|name>` | change node and list its contents; absolute (`/a/b`), relative (`a/b`), `..`, `.`; fuzzy names allowed |
| `.pwd` | print current path |
| `.ls` / `.ll` | list children (`/`) and values; `.ll` also shows local timestamps |
| `.ts` | timestamp of the current node |
| `.get <key>` | read a value |
| `.set <key> <value>` | set a value; numbers, `true`/`false` and JSON are auto-detected |
| `.del <key>` | delete a value |
| `.mkdir <name>` | create a child node |
| `.rm <name> [--force]` | delete a child node and everything below it |
| `.save` | encrypt and write the vault file (password entered twice and verified) |
| `.export <file>` | export **decrypted** JSON (asks for confirmation) |
| `.import <file>` | merge a plaintext notebook JSON into the current one (see below) |
| `.discard` | drop unsaved changes, reload from disk |
| `.pass <length>` | generate a random password (lowercase, uppercase, digits, symbols) |
| `.help` / `.exit` | |

Timestamps are shown in the machine's local time zone with its UTC offset.
`.save` asks for the password twice; if the two entries differ, or the freshly
written ciphertext cannot be decrypted again with it, nothing is written.

Values with spaces must be quoted: `.set title "my long title"`.

### Importing another notebook

`.import file.json` merges a plaintext notebook (for example one produced by
`.export`) into the open notebook, starting at the root. Nodes are matched by
their path from the root; when both trees have a node, their contents are
unioned instead of the newer one replacing the whole subtree:

- path only in the imported file: added as-is (its `ts` values are kept);
- path only in the current notebook: **always kept**. Merging only ever adds,
  so two notebooks used for different work both keep their branches (deleting
  is still done explicitly with `.rm` / `.del`);
- a value present on both sides with different content: the newer one wins
  (a tie keeps the current value);
- kept decisions change nothing, so kept nodes keep their own `ts`.

A node whose content changed ends up with
`min(max(imported ts, current ts), now)`, so no timestamp can be in the future.
The merge is all-or-nothing: the file is parsed and merged into a copy first,
so a malformed file leaves the notebook untouched. Nothing is written until
`.save`. The full algorithm, as pseudo code with explanations, is in
[`docs/merge-algorithm.md`](docs/merge-algorithm.md).

Fuzzy names use edit distance. `wrk` resolves to `work` when it is the only
close match; when several children match, the candidates are listed and you
must give an exact name.

## Encrypting arbitrary files

`dist\filetool.cmd` encrypts and decrypts any file, not just notebooks:

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
- `info` needs no password and does not decrypt anything. It reports the format
  version, KDF, KDF parameters, key/salt/nonce lengths, the salt and nonce in
  hex, padding length, payload size and total overhead.
- Files are read fully into memory and written atomically, so very large files
  need correspondingly large heap (`java -Xmx...`).

## Data model

Every node is a JSON object. A key whose value is an object is a **child node**;
any other value is a **leaf value**. There is no separate `children` field.

```json
{
  "ts": 1794312289193,
  "title": "root",
  "work": {
    "ts": 1794312289193,
    "title": "project",
    "deep": {
      "ts": 1794312289193,
      "note": "hello"
    }
  }
}
```

- Every object node has a reserved numeric `ts` field (epoch milliseconds).
- Any change updates the affected node's `ts` **and every ancestor up to the
  root**.
- Setting a value equal to the existing one is a no-op and does not change `ts`.
- Navigation is read-only and never touches `ts`.
- `ts` cannot be created, overwritten or deleted as a user key.

## Security notes

- **KDF:** Argon2id, 64 MiB memory, 3 iterations, 1 lane (configurable through
  `Vault.encrypt(..., memoryKib, iterations, parallelism)`).
- **Cipher:** AES-256-GCM. The whole header (including salt and KDF parameters)
  is authenticated as associated data, so parameters cannot be downgraded.
- **No file signature.** The format has no magic bytes. Every file starts with a
  one-byte padding length followed by 0–255 random bytes, so files have no fixed
  prefix or fixed size and cannot be fingerprinted by their first bytes. The
  remaining header fields are random (salt, nonce) or high-entropy values, and
  the whole file reads as a uniform random stream.
- When reading a file, KDF parameters are range-checked (8 MiB–1 GiB, ≤ 1000
  iterations) so a crafted header cannot exhaust memory.
- A wrong password and a tampered file are indistinguishable: both fail the GCM
  tag check.
- No plaintext temporary files are created. `.save` writes through a sibling
  temp file containing only ciphertext, then renames atomically.
- Key and plaintext buffers are zeroed after use; passwords are read with
  `Console.readPassword` and held as `char[]` that are wiped.

## Library use

```java
// Crypto only
byte[] file = Vault.encrypt(plaintextBytes, password);
byte[] plain = Vault.decrypt(file, password);

// Tree only
NodeTree t = new NodeTree();
t.mkdir("work");
t.cd("work");
t.set("title", JsonNodeFactory.instance.textNode("hello"));
String json = t.toJson();
```

`NodeTree` is not thread safe; serialize access externally.
