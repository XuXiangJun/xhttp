#compdef xhttp
# zsh completion for xhttp
# install: put this file as _xhttp somewhere on your $fpath

_xhttp() {
    _arguments -s -S \
        '(-X --request)'{-X,--request}'[HTTP method]:method:(GET POST PUT PATCH DELETE HEAD OPTIONS)' \
        '*'{-H,--header}'[header "Name: value"]:header:' \
        '*'{-P,--param}'[query parameter]:name=value:' \
        '*'{-V,--path-variable}'[replace {name} in the path]:name=value:' \
        '(-A --user-agent)'{-A,--user-agent}'[User-Agent header]:string:' \
        '(-e --referer)'{-e,--referer}'[Referer header]:url:' \
        '(-I --head)'{-I,--head}'[send HEAD and print headers only]' \
        '(-G --get)'{-G,--get}'[send --data as a query string]' \
        '*'{-d,--data}'[request body (@file, @-)]:data:' \
        '*--data-raw[request body, @ not special]:data:' \
        '*--data-binary[request body, files verbatim]:data:' \
        '*--data-urlencode[URL-encoded body part]:data:' \
        '*--data-file[stream the body from a file]:file:_files' \
        '*'{-F,--form}'[multipart field (name=@file)]:field:' \
        '*--form-string[literal multipart field]:field:' \
        '--json[set JSON Content-Type and Accept]' \
        '(-o --output)'{-o,--output}'[write the body to a file]:file:_files' \
        '(-O --remote-name)'{-O,--remote-name}'[name the file after the URL]' \
        '--output-dir[directory for -o/-O]:dir:_files -/' \
        '--create-dirs[create missing directories]' \
        '(-C --continue-at)'{-C,--continue-at}'[resume a download]:offset:(-)' \
        '(-i --include)'{-i,--include}'[print status line and headers]' \
        '--print[output selection]:spec:(b hb Hhb HBhb)' \
        '(--pretty --no-pretty)--pretty[pretty-print JSON]' \
        '(--pretty --no-pretty)--no-pretty[do not pretty-print JSON]' \
        '(--color --no-color)--color[colourize output]' \
        '(--color --no-color)--no-color[do not colourize output]' \
        '--select[extract from a JSON body]:path:' \
        '(-w --write-out)'{-w,--write-out}'[summary format]:format:' \
        '(-s --silent)'{-s,--silent}'[no progress or errors]' \
        '(-S --show-error)'{-S,--show-error}'[keep errors with --silent]' \
        '(-v --verbose)'{-v,--verbose}'[headers to stderr]' \
        '--trace[verbose plus bodies]' \
        '(-f --fail)'{-f,--fail}'[exit 22 on HTTP >= 400]' \
        '(-L --allow-redirects --no-allow-redirects)'{-L,--allow-redirects}'[follow redirects]' \
        '(-L --allow-redirects --no-allow-redirects)--no-allow-redirects[do not follow redirects]' \
        '--max-redirs[redirect limit]:count:' \
        '--connect-timeout[connect timeout in seconds]:seconds:' \
        '--socket-timeout[idle socket timeout in seconds]:seconds:' \
        '--request-timeout[overall timeout in seconds]:seconds:' \
        '(-m --max-time)'{-m,--max-time}'[overall timeout in seconds]:seconds:' \
        '--retry[retry count]:count:' \
        '--retry-delay[delay between retries]:seconds:' \
        '--limit-rate[download speed cap]:rate:' \
        '(-x --proxy)'{-x,--proxy}'[proxy URL]:url:' \
        '--proxy-user[proxy credentials]:user\:pass:' \
        '--noproxy[hosts bypassing the proxy]:list:' \
        '(-u --user)'{-u,--user}'[basic credentials]:user[\:pass]:' \
        '--bearer[bearer token]:token:' \
        '(-n --netrc)'{-n,--netrc}'[use ~/.netrc]' \
        '--netrc-file[netrc file]:file:_files' \
        '(-k --insecure)'{-k,--insecure}'[skip certificate verification]' \
        '--cacert[CA bundle]:file:_files' \
        '--capath[CA directory]:dir:_files -/' \
        '*'{-c,--cookie}'[cookie name=value]:cookie:' \
        '(-b --cookie-file)'{-b,--cookie-file}'[load a cookie file]:file:_files' \
        '--cookie-jar[save cookies to a file]:file:_files' \
        '--session[named cookie session]:name:' \
        '--config[options file]:file:_files' \
        '--no-config[ignore the options file]' \
        '(-g --globoff)'{-g,--globoff}'[disable URL globbing]' \
        '--dry-run[print the request and exit]' \
        '--version[print the version]' \
        '(-h --help)'{-h,--help}'[print help]' \
        '*:url:_urls'
}

_xhttp "$@"
