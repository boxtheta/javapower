# Contributing

See [docs/architecture.md](docs/architecture.md) for the machine model and module layout, and
[docs/development.md](docs/development.md) for the build and test environment.

## Correctness

Correctness is established against QEMU, not from memory of the spec: every instruction group
needs differential tests whose expected values came from running the real instruction under QEMU.

QEMU is GPL and this project is Apache 2.0. Read QEMU to learn behaviour; do not copy or
transliterate its code.

Behaviour that exists only to work around a known guest bug is a quirk. List it in
[docs/guest-quirks.md](docs/guest-quirks.md): how the guest is recognised, what changes, what is
left out, and when the quirk can be removed. Do this in the same change that adds, alters or
removes the quirk.

## Code conventions

- Java 21 language level; `-Werror` is on. JUnit 5 for tests, which run on JDK 21 (`test`) and
  JDK 27 (`test27`).
- The multi-release classes (`src/main/java22` in `core` and `cpu-int`) have a Java 21 and a
  Java 22 version. Change both together; `test` covers the 21 one, `test27` the 22 one.
- No new runtime dependencies. ASM is the one bundled library; don't add others.
- Comments use ISA bit numbering (bit 0 = MSB) and say so; code uses Java shifts.
- Instruction handlers are stateless `Op`s registered into a `DecodeTable` through an `InsnSet`.
  They must raise `PpcException` before modifying state.
- Public types are library API; see [Library API](docs/architecture.md#library-api).
- Modules other than `core` do not depend on each other; see
  [Modules](docs/architecture.md#modules).
