# Contributing to NexusUI

Thank you for your interest in contributing! Here's how you can help.

## Reporting Bugs

1. Check [existing issues](https://github.com/TheAlanK/NexusUI/issues) first to avoid duplicates
2. Open a new issue with:
   - Starsector version and mod list
   - Steps to reproduce the bug
   - Expected vs. actual behavior
   - Relevant `starsector.log` excerpts

## Suggesting Features

Open an issue with the **enhancement** label describing:
- What you want to achieve
- Why the current API doesn't support it
- A rough idea of the approach (optional)

## Pull Requests

1. **Fork** the repository
2. **Create a branch** from `master` (`git checkout -b feature/my-feature`)
3. **Make your changes** following the code style below
4. **Test** in-game with Starsector 0.98a-RC7
5. **Open a PR** with a clear description of the changes

### Code Style

- **Java 8 compatible** — no lambdas, no `var`, no try-with-resources, no diamond operator
- Compile with `-source 8 -target 8`
- Follow existing naming conventions (camelCase methods, UPPER_SNAKE constants)
- Add Javadoc comments for public API methods
- Keep classes focused and single-purpose

### Build Requirements

- JDK 8+ (compiled with `-source 8 -target 8`)
- Starsector 0.98a-RC7 with `starfarer.api.jar` on classpath
- [LazyLib](https://fractalsoftworks.com/forum/index.php?topic=5444.0) JAR on classpath

### What We're Looking For

- New `NexusPage` implementations for the ecosystem
- REST API endpoint additions
- Performance improvements
- Bug fixes
- Documentation improvements

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
