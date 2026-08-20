# xhttp

A small, script-friendly HTTP client for the command line, built with Kotlin/Native and
[Ktor](https://ktor.io) (curl engine). It reads like `curl` — same short flags, same exit codes —
with JSON pretty-printing, path variables, multipart forms and a JSON field selector built in.

Single static-ish binary, no runtime, no interpreter.

## Build

```shell
sudo apt-get install libcurl4-gnutls-dev      # Linux build dependency
./gradlew installBinary                       # -> build/install/xhttp (stripped)
./gradlew check                               # runs the unit tests
./gradlew distTar                             # -> build/distributions/xhttp-<version>.tar.gz
```

The raw Kotlin/Native output stays at `build/bin/linuxX64/releaseExecutable/xhttp.kexe`;
`installBinary` copies it to `build/install/xhttp` and strips it, ready to drop on your `PATH`.

## Usage

```text
xhttp [options] <URL>...
```

The URL is a positional argument and several may be given. A missing scheme is filled in as
`http://`, so `xhttp example.com/api` works.

### Examples

```shell
# GET, pretty-printed and coloured when the output is a terminal
xhttp https://api.github.com/repos/JetBrains/kotlin

# Pull one field out of a JSON response (great in $(...) )
TOKEN=$(xhttp -s --json -d '{"user":"me"}' https://api.example.com/login --select .token)

# Every id in an array
xhttp -s https://api.example.com/items --select '.items[].id'

# Query parameters and headers
xhttp -P page=2 -P per_page=10 -H 'X-Api-Key: secret' https://api.example.com/items

# POST JSON (--json sets Content-Type/Accept and validates the payload)
xhttp --json -d '{"hello":"world"}' https://api.example.com/echo

# Bodies from files or stdin; big files are streamed, never buffered
xhttp -X POST --data-binary @payload.bin https://api.example.com/upload
cat payload.json | xhttp --json -d @- https://api.example.com/echo

# Form encoding with automatic URL-encoding
xhttp --data-urlencode 'q=a b&c' https://api.example.com/search

# Send --data as a query string instead of a body
xhttp -G -d 'q=kotlin&lang=en' https://api.example.com/search

# Multipart upload (streamed; ;type= and ;filename= are optional)
xhttp -F username=alice -F 'avatar=@./avatar.png;type=image/png' https://api.example.com/upload

# Path variables: {name} is substituted and percent-encoded
xhttp -V id=42 https://api.example.com/users/{id}

# Authentication. Omit the password and it is prompted for, so it stays out of ps and history
xhttp -u alice https://api.example.com/secure
xhttp --bearer "$TOKEN" https://api.example.com/secure
xhttp --netrc https://api.example.com/secure

# Download with progress, a speed cap and resume support
xhttp -o archive.tar.gz --limit-rate 2M https://example.com/archive.tar.gz
xhttp -C - -o archive.tar.gz https://example.com/archive.tar.gz

# Several URLs at once, with curl-style globbing
xhttp -O --output-dir ./out 'https://example.com/page[1-5].html'

# Cookie jar that survives between runs
xhttp --session mysite https://example.com/login -d user=me -d pass=secret
xhttp --session mysite https://example.com/profile

# Retries, timeouts and scripting
xhttp --retry 3 --max-time 30 --fail https://example.com/flaky
xhttp -s -o /dev/null -w '%{http_code} %{time_total}\n' https://example.com

# See exactly what would be sent, without sending it
xhttp --dry-run -X PUT -d '{"a":1}' --json https://example.com/x
```

### Options

| Option | Description |
| ------ | ----------- |
| **Request** | |
| `-X, --request METHOD` | HTTP method (default `GET`, or `POST` when there is a body) |
| `-H, --header "Name: value"` | Header; `Name;` sends an empty one (repeatable) |
| `-P, --param "name=value"` | Query parameter (repeatable) |
| `-V, --path-variable "name=value"` | Replace `{name}` in the URL path (repeatable) |
| `-A, --user-agent STRING` | User-Agent header |
| `-e, --referer URL` | Referer header |
| `-I, --head` | Send `HEAD` and print only the response headers |
| `-G, --get` | Send `--data` as a query string instead of a body |
| **Body** | |
| `-d, --data DATA` | Body; `@file` reads a file, `@-` reads stdin (repeatable, joined with `&`) |
| `--data-raw DATA` | Like `--data` but `@` is not special |
| `--data-binary DATA` | Like `--data` but files are sent verbatim and streamed |
| `--data-urlencode DATA` | URL-encode the value: `[name=]value`, `[name]@file` |
| `--data-file FILE` | Stream the body from a file |
| `-F, --form "name=value"` | Multipart field; `name=@file` uploads, `name=<file` reads the value (repeatable) |
| `--form-string "name=value"` | Multipart field whose value is always literal |
| `--json` | Set `Content-Type`/`Accept` to JSON and validate the payload |
| **Output** | |
| `-o, --output FILE` | Write the body to `FILE` (`-` means stdout) |
| `-O, --remote-name` | Save to a file named after the remote path |
| `--output-dir DIR` | Directory for `-o`/`-O` |
| `--create-dirs` | Create missing directories for the output file |
| `-C, --continue-at -` | Resume a partial download |
| `-i, --include` | Print the status line and headers before the body |
| `--print SPEC` | Choose what goes to stdout: `H` request headers, `B` request body, `h`/`b` response |
| `--pretty` / `--no-pretty` | Pretty-print JSON (default: on for a terminal) |
| `--color` / `--no-color` | Colourize JSON and headers (default: on for a terminal) |
| `--select PATH` | Extract from a JSON body: `.a.b[0]`, `.items[].id`, `["key"]` |
| `-w, --write-out FORMAT` | Summary after the transfer (see below) |
| `-s, --silent` | No progress, no error messages |
| `-S, --show-error` | Keep error messages even with `--silent` |
| `-v, --verbose` | Request and response headers to stderr (credentials redacted) |
| `--trace` | Like `--verbose`, plus the request body and unredacted headers |
| `-f, --fail` | Suppress the body and exit `22` on HTTP >= 400 |
| **Connection** | |
| `-L, --allow-redirects` / `--no-allow-redirects` | Follow redirects (default: on) |
| `--max-redirs N` | Redirect limit (default: 20) |
| `--connect-timeout SEC` | Connection timeout |
| `--socket-timeout SEC` | Idle socket timeout |
| `--request-timeout SEC`, `-m, --max-time SEC` | Overall timeout |
| `--retry N` | Retry transient failures and 5xx/429 |
| `--retry-delay SEC` | Fixed delay between retries (default: exponential back-off) |
| `--limit-rate RATE` | Cap the download speed, e.g. `200K`, `2M` |
| `-x, --proxy URL` | Proxy: `http://`, `https://` or `socks5://host:port` |
| `--proxy-user USER:PASS` | Proxy credentials |
| `--noproxy LIST` | Hosts that bypass the proxy; `*` disables it |
| **Authentication and TLS** | |
| `-u, --user USER[:PASS]` | HTTP Basic; the password is prompted for when omitted |
| `--bearer TOKEN` | Bearer token |
| `-n, --netrc`, `--netrc-file FILE` | Take credentials from `~/.netrc` |
| `-k, --insecure` | Do not verify the server certificate |
| `--cacert FILE`, `--capath DIR` | Trust a private CA |
| **Cookies** | |
| `-c, --cookie "name=value"` | Send a cookie; a file name loads a jar (repeatable) |
| `-b, --cookie-file FILE` | Load cookies from a Netscape cookie file |
| `--cookie-jar FILE` | Write cookies back when the run ends |
| `--session NAME` | Load and store cookies in `~/.config/xhttp/sessions/NAME.txt` |
| **General** | |
| `--config FILE`, `--no-config` | Default options file (see below) |
| `-g, --globoff` | Do not expand `{a,b}` and `[1-9]` in the URL |
| `--dry-run` | Print the request that would be sent and exit |
| `--version`, `-h, --help` | |

### `--write-out` variables

`%{http_code}`, `%{response_code}`, `%{url_effective}`, `%{num_redirects}`, `%{size_download}`,
`%{size_header}`, `%{speed_download}`, `%{time_total}`, `%{time_starttransfer}`,
`%{content_type}`, and `%{json}` for all of them at once. `\n`, `\t` and `\\` are expanded.

Variables curl derives from its per-phase timers (`%{time_namelookup}`, `%{time_connect}`,
`%{time_appconnect}`) are rejected rather than reported as a made-up `0`: the Ktor curl engine does
not expose them.

### Configuration file

`~/.config/xhttp/config` (or `$XDG_CONFIG_HOME/xhttp/config`, `$XHTTP_CONFIG`, `--config FILE`)
holds default options, one per line, using long option names without the dashes:

```ini
# ~/.config/xhttp/config
user-agent = my-tool/1.0
header     = X-Api-Key: secret
max-time   = 30
pretty
```

Command-line options win over the file; repeatable options (headers, cookies, parameters)
accumulate. `--no-config` ignores the file entirely.

### Environment

`HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY` (and their lowercase forms), `NO_COLOR`,
`XHTTP_CONFIG`, `XDG_CONFIG_HOME`.

## Behaviour notes

- Response bodies reach stdout **byte for byte**: no charset round-trip and no added newline, so
  `xhttp url > file` and `xhttp url | sha256sum` are exact. (A terminal gets one trailing newline
  so the shell prompt does not start mid-line.)
- Diagnostics, progress and `-v` output go to **stderr**, so `xhttp ... | jq` works.
- A non-textual body is only refused when stdout is a **terminal**; redirecting or piping always
  gets the raw bytes.
- Bodies are streamed in both directions. Only `--pretty` and `--select`, which need the whole
  document, buffer it.
- Redirects are followed by xhttp itself, not by libcurl, so `--max-redirs` works, credentials are
  dropped when the redirect crosses to another host, and `%{num_redirects}` is accurate.
- Compressed responses are decompressed automatically by libcurl; there is no `--compressed` flag
  because there is nothing to switch on.
- `-v` redacts `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie`. Use `--trace`
  when you really need the values.
- Exit codes follow curl: `0` ok, `2` usage, `3` bad URL, `6` DNS, `7` connection refused,
  `18` partial transfer, `22` `--fail` on HTTP >= 400, `23` write error, `26` read error,
  `28` timeout, `35` TLS handshake, `47` too many redirects, `60` untrusted certificate.

## Differences from curl

| | xhttp | curl |
| --- | --- | --- |
| `-c` | send a cookie | write the cookie jar |
| `-b` | load a cookie file | send a cookie / load a file |
| `--pretty`, `--select`, `-P`, `-V` | JSON and URL conveniences | not available |
| `-C` | only `-C -` (auto offset) | any byte offset |
| client certificates, unix sockets, HTTP/2+ toggles | not available | available |

## Shell completions

`completions/xhttp.bash`, `completions/xhttp.zsh` and `completions/xhttp.fish` are in the
distribution tarball; the man page is `docs/xhttp.1`.

## License

MIT — see [LICENSE](LICENSE).
