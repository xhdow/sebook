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
src/test/java/notebook/
  SelfCheck.java       dependency-free test suite
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
| `.discard` | drop unsaved changes, reload from disk |
| `.pass <length>` | generate a random password (lowercase, uppercase, digits, symbols) |
| `.help` / `.exit` | |

Timestamps are shown in the machine's local time zone with its UTC offset.
`.save` asks for the password twice; if the two entries differ, or the freshly
written ciphertext cannot be decrypted again with it, nothing is written.

Values with spaces must be quoted: `.set title "my long title"`.

Fuzzy names use edit distance. `wrk` resolves to `work` when it is the only
close match; when several children match, the candidates are listed and you
must give an exact name.

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
    "tags": ["a", "b"],
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
- Files written by older builds (starting with `NBT1`) are rejected with an
  explicit "legacy vault format" message.
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
