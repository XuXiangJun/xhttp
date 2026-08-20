# fish completion for xhttp
# install: cp completions/xhttp.fish ~/.config/fish/completions/

complete -c xhttp -f

# Request
complete -c xhttp -s X -l request     -r -d 'HTTP method' -xa 'GET POST PUT PATCH DELETE HEAD OPTIONS'
complete -c xhttp -s H -l header      -r -d 'Header "Name: value"'
complete -c xhttp -s P -l param       -r -d 'Query parameter name=value'
complete -c xhttp -s V -l path-variable -r -d 'Replace {name} in the path'
complete -c xhttp -s A -l user-agent  -r -d 'User-Agent header'
complete -c xhttp -s e -l referer     -r -d 'Referer header'
complete -c xhttp -s I -l head           -d 'Send HEAD, print headers only'
complete -c xhttp -s G -l get            -d 'Send --data as a query string'

# Body
complete -c xhttp -s d -l data        -r -d 'Request body (@file, @-)'
complete -c xhttp      -l data-raw    -r -d 'Body, @ not special'
complete -c xhttp      -l data-binary -r -d 'Body, files verbatim'
complete -c xhttp      -l data-urlencode -r -d 'URL-encoded body part'
complete -c xhttp      -l data-file   -rF -d 'Stream the body from a file'
complete -c xhttp -s F -l form        -r -d 'Multipart field (name=@file)'
complete -c xhttp      -l form-string -r -d 'Literal multipart field'
complete -c xhttp      -l json           -d 'JSON Content-Type/Accept'

# Output
complete -c xhttp -s o -l output      -rF -d 'Write the body to a file'
complete -c xhttp -s O -l remote-name    -d 'Name the file after the URL'
complete -c xhttp      -l output-dir  -rF -d 'Directory for -o/-O'
complete -c xhttp      -l create-dirs    -d 'Create missing directories'
complete -c xhttp -s C -l continue-at -r -d 'Resume a download' -xa '-'
complete -c xhttp -s i -l include        -d 'Print status line and headers'
complete -c xhttp      -l print       -r -d 'H/B/h/b output selection'
complete -c xhttp      -l pretty         -d 'Pretty-print JSON'
complete -c xhttp      -l no-pretty      -d 'Do not pretty-print JSON'
complete -c xhttp      -l color          -d 'Colourize output'
complete -c xhttp      -l no-color       -d 'Do not colourize output'
complete -c xhttp      -l select      -r -d 'Extract from a JSON body'
complete -c xhttp -s w -l write-out   -r -d 'Summary format'
complete -c xhttp -s s -l silent         -d 'No progress or errors'
complete -c xhttp -s S -l show-error     -d 'Keep errors with --silent'
complete -c xhttp -s v -l verbose        -d 'Headers to stderr'
complete -c xhttp      -l trace          -d 'Verbose plus bodies'
complete -c xhttp -s f -l fail           -d 'Exit 22 on HTTP >= 400'

# Connection
complete -c xhttp -s L -l allow-redirects    -d 'Follow redirects'
complete -c xhttp      -l no-allow-redirects -d 'Do not follow redirects'
complete -c xhttp      -l max-redirs      -r -d 'Redirect limit'
complete -c xhttp      -l connect-timeout -r -d 'Connect timeout (s)'
complete -c xhttp      -l socket-timeout  -r -d 'Idle socket timeout (s)'
complete -c xhttp      -l request-timeout -r -d 'Overall timeout (s)'
complete -c xhttp -s m -l max-time        -r -d 'Overall timeout (s)'
complete -c xhttp      -l retry           -r -d 'Retry count'
complete -c xhttp      -l retry-delay     -r -d 'Delay between retries (s)'
complete -c xhttp      -l limit-rate      -r -d 'Download speed cap'
complete -c xhttp -s x -l proxy           -r -d 'Proxy URL'
complete -c xhttp      -l proxy-user      -r -d 'Proxy credentials'
complete -c xhttp      -l noproxy         -r -d 'Hosts bypassing the proxy'

# Auth and TLS
complete -c xhttp -s u -l user       -r -d 'Basic credentials'
complete -c xhttp      -l bearer     -r -d 'Bearer token'
complete -c xhttp -s n -l netrc         -d 'Use ~/.netrc'
complete -c xhttp      -l netrc-file -rF -d 'netrc file'
complete -c xhttp -s k -l insecure      -d 'Skip certificate verification'
complete -c xhttp      -l cacert     -rF -d 'CA bundle'
complete -c xhttp      -l capath     -rF -d 'CA directory'

# Cookies
complete -c xhttp -s c -l cookie      -r -d 'Cookie name=value'
complete -c xhttp -s b -l cookie-file -rF -d 'Load a cookie file'
complete -c xhttp      -l cookie-jar  -rF -d 'Save cookies to a file'
complete -c xhttp      -l session     -r -d 'Named cookie session'

# General
complete -c xhttp      -l config    -rF -d 'Options file'
complete -c xhttp      -l no-config     -d 'Ignore the options file'
complete -c xhttp -s g -l globoff       -d 'Disable URL globbing'
complete -c xhttp      -l dry-run       -d 'Print the request and exit'
complete -c xhttp      -l version       -d 'Print the version'
complete -c xhttp -s h -l help          -d 'Print help'
