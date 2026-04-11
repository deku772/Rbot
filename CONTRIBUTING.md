# Contributing to ANDBOTT

Thanks for your interest in contributing to ANDBOTT!

## Getting Started

1. Fork the repository
2. Clone your fork
3. Set up the development environment:
   - Install Android Studio or Android SDK + NDK
   - JDK 17+
   - Run `./gradlew assembleDebug` to verify build

## Making Changes

1. Create a feature branch: `git checkout -b feature/your-feature`
2. Make your changes
3. Write tests for new functionality
4. Run tests: `./gradlew :app:testDebugUnitTest`
5. Build: `./gradlew assembleDebug`
6. Commit with a descriptive message
7. Push and open a Pull Request

## Code Style

- Java code follows standard Android conventions
- Use meaningful variable and method names
- Keep methods focused and small
- Add comments only where logic isn't self-evident

## Reporting Issues

Use the [GitHub issue tracker](../../issues) to report bugs or request features.

## License

By contributing, you agree that your contributions will be licensed under the GPLv3.
