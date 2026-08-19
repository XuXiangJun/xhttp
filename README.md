# xhttp

A small, script-friendly HTTP client for the command line, built with Kotlin/Native and
[Ktor](https://ktor.io) (curl engine). Heavily inspired by `curl` but with a few quality-of-life
extras (JSON pretty-printing, path variables, built-in multipart forms).

## Build

### Required
#### Linux
```shell
sudo apt-get install libcurl4-gnutls-dev
```

```shell
./gradlew linkReleaseExecutableNative
# binary: build/bin/native/releaseExecutable/native.kexe
```

## Usage

```text
xhttp [options] <URL>
```

The URL is a positional argument. The binary is a single binary; examples below assume it is on
your `PATH` as `xhttp`.

### Examples

```shell
# GET
xhttp https://api.github.com/repos/JetBrains/kotlin

# JSON pretty-print (Content-Type: application/json)
xhttp --pretty https://api.github.com/repos/JetBrains/kotlin

# Query parameters
xhttp -P page=2 -P per_page=10 https://api.example.com/items

# Custom header & method
xhttp -X POST -H 'Content-Type: application/json' -d '{"hello":"world"}' https://api.example.com/echo

# `--json` sets Content-Type/Accept and validates the payload
xhttp --json -X POST -d '{"hello":"world"}' https://api.example.com/echo

# Request body from a file (`--data-file`, or `-d @file`)
xhttp -X POST --data-file payload.json https://api.example.com/echo
xhttp -X POST -d @payload.json https://api.example.com/echo

# Multipart form (attach a file with `name=@file`)
xhttp -F username=alice -F avatar=@./avatar.png https://api.example.com/upload

# Path variables (`{name}` in the URL is replaced and percent-encoded)
xhttp -V id=42 https://api.example.com/users/{id}

# Authentication
xhttp -u user:pass https://api.example.com/secure          # HTTP Basic
xhttp --bearer "$TOKEN" https://api.example.com/secure     # Bearer token

# Download a file with progress
xhttp -o archive.tar.gz --progress https://example.com/archive.tar.gz

# Full request/response dump (to stderr), follows redirects
xhttp -vL https://example.com

# Fail with exit code 22 on HTTP error (for scripts)
xhttp --fail https://example.com/status/500

# Print status line + headers with the body (`-i`), like curl
xhttp -i https://example.com

# Timeouts (seconds) and TLS/proxy
xhttp --connect-timeout 5 --request-timeout 30 https://example.com
xhttp -k https://self-signed.example.com
xhttp --proxy http://127.0.0.1:8080 https://example.com
```

### Options

| Option | Description |
| ------ | ----------- |
| `-X, --request METHOD` | HTTP method (default `GET`) |
| `-H, --header "Name: value"` | Request header (repeatable) |
| `-P, --param "name=value"` | Query parameter (repeatable) |
| `-V, --path-variable "name=value"` | Replace `{name}` in the URL path (repeatable) |
| `-d, --data TEXT` | Request body; `@file` reads from a file |
| `--data-file FILE` | Request body from a file |
| `-F, --form "name=value"` | Multipart field; `name=@file` uploads a file (repeatable) |
| `-o, --output FILE` | Write the response body to a file |
| `--pretty` | Pretty-print JSON responses |
| `-v, --verbose` | Full request/response exchange to stderr |
| `-i, --include` | Include response status + headers in stdout output |
| `-L, --allow-redirects` | Follow redirects (default: true) |
| `--fail` | Exit with status 22 when HTTP status >= 400 |
| `--json` | Set `Content-Type`/`Accept` to `application/json` and validate `--data` |
| `-u, --user user:pass` | HTTP Basic authentication |
| `--bearer TOKEN` | Bearer token authentication |
| `-k, --insecure` | Disable TLS certificate verification |
| `--proxy URL` | HTTP proxy (e.g. `http://127.0.0.1:8080`) |
| `-c, --cookie "name=value"` | Cookie (repeatable) |
| `--session` | In-memory cookie jar reused across redirects |
| `--progress` | Download progress to stderr (with `--output`) |
| `--connect-timeout SEC` | Connection timeout in seconds |
| `--socket-timeout SEC` | Socket timeout in seconds |
| `--request-timeout SEC` | Overall request timeout in seconds |
| `--version` | Print version and exit |
| `-h, --help` | Print usage |

## Behavior notes

- Normal output goes to **stdout**; diagnostics and verbose output go to **stderr**, so
  `xhttp ... | jq` works.
- Invalid/blank URLs, missing files and conflicting options exit with a non-zero status and a
  short `Error:` message instead of a stack trace.
- A non-textual response body (e.g. `image/*`, `application/octet-stream`, `application/zip`)
  is never dumped to the terminal; use `-o/--output` to save it.
- `--pretty` degrades gracefully: invalid JSON is printed as-is instead of crashing.
