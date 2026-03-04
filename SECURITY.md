# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.9.x   | Yes       |

## Security Model

NexusUI runs an embedded HTTP server that **only binds to `127.0.0.1` (localhost)**. This means:

- The REST API is **not accessible** from other machines on the network
- Only local processes on the same computer can access the API
- No authentication is required since access is already restricted to localhost

### Important Notes

- The HTTP server runs on port **5959** by default
- All API responses include CORS headers for browser compatibility
- Game commands executed via the API run with full game-thread permissions
- The server is only active while a save is loaded in Starsector

## Reporting a Vulnerability

If you discover a security vulnerability, please:

1. **Do not** open a public issue
2. Open a private security advisory via GitHub's [Security Advisories](https://github.com/TheAlanK/NexusUI/security/advisories/new) feature
3. Include a description of the vulnerability and steps to reproduce

We will respond within 7 days and work on a fix promptly.
