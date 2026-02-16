# Rider NuGet Auto-Fill Plugin

A JetBrains Rider plugin that automatically detects and fills NuGet feed credential dialogs with securely stored credentials.

## Features

- **Automatic Detection**: Automatically detects NuGet credential dialogs in Rider
- **Secure Storage**: Credentials stored securely using system keychain
- **Multi-Feed Support**: Manage credentials for multiple NuGet feeds (NuGet.org, Azure DevOps, Artifactory, GitHub Packages, etc.)
- **Auto-Submit**: Optional automatic form submission after filling credentials
- **Easy Management**: User-friendly interface for managing feed credentials

## Installation

1. Download the plugin from the JetBrains Marketplace
2. In Rider, go to **Settings/Preferences → Plugins → Install Plugin from Disk**
3. Select the downloaded plugin file
4. Restart Rider

## Usage

1. Go to **Tools → Manage NuGet Credentials**
2. Add your NuGet feed credentials
3. The plugin will automatically fill credentials when Rider requests authentication

## Supported NuGet Feeds

- NuGet.org
- Azure DevOps Artifacts
- JFrog Artifactory
- Sonatype Nexus
- GitHub Packages
- MyGet
- Any custom NuGet feed requiring authentication

## Configuration

### Adding a Feed

1. Open **Tools → Manage NuGet Credentials**
2. Click **Add Feed**
3. Enter your feed URL, username, and password/API key
4. Enable **Auto-submit** if you want automatic form submission
5. Click **OK**

### Quick Setup Templates

The plugin includes quick setup templates for popular feeds:
- **NuGet.org**: Pre-fills the official NuGet feed URL
- **Azure DevOps**: Guided setup for Azure DevOps feeds
- **GitHub Packages**: Guided setup for GitHub package feeds

## Security

- Credentials are stored using IntelliJ's secure PasswordSafe API
- Passwords are encrypted using the system keychain
- Non-sensitive configuration is stored separately from passwords
- Individual feeds can be enabled/disabled without removing credentials

## Requirements

- JetBrains Rider 2024.1 or later
- IntelliJ Platform Build 241 or later

## Building from Source

```bash
./gradlew build
```

The plugin will be built in `build/distributions/`.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

For issues, feature requests, or contributions, please visit the [GitHub repository](https://github.com/ms-tt-four/rider-nuget-auto-fill-plugin).
