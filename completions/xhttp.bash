# bash completion for xhttp
# install: source this file, or drop it in /etc/bash_completion.d/

_xhttp() {
    local cur prev
    COMPREPLY=()
    cur="${COMP_WORDS[COMP_CWORD]}"
    prev="${COMP_WORDS[COMP_CWORD-1]}"

    local long_opts="--request --header --param --path-variable --user-agent --referer --head --get
        --data --data-raw --data-binary --data-urlencode --data-file --form --form-string --json
        --output --remote-name --output-dir --create-dirs --continue-at --include --print --pretty
        --no-pretty --color --no-color --select --write-out --silent --show-error --verbose --trace
        --fail --allow-redirects --no-allow-redirects --max-redirs --connect-timeout
        --socket-timeout --request-timeout --max-time --retry --retry-delay --limit-rate --proxy
        --proxy-user --noproxy --user --bearer --netrc --netrc-file --insecure --cacert --capath
        --cookie --cookie-file --cookie-jar --session --config --no-config --globoff --dry-run
        --version --help"

    case "$prev" in
        -o|--output|-b|--cookie-file|--cookie-jar|--data-file|--cacert|--config|--netrc-file)
            COMPREPLY=( $(compgen -f -- "$cur") ); return 0 ;;
        --output-dir|--capath)
            COMPREPLY=( $(compgen -d -- "$cur") ); return 0 ;;
        -X|--request)
            COMPREPLY=( $(compgen -W "GET POST PUT PATCH DELETE HEAD OPTIONS" -- "$cur") ); return 0 ;;
        --print)
            COMPREPLY=( $(compgen -W "b hb Hhb HBhb" -- "$cur") ); return 0 ;;
        -w|--write-out)
            COMPREPLY=( $(compgen -W '%{http_code} %{time_total} %{size_download} %{url_effective} %{json}' -- "$cur") ); return 0 ;;
        -C|--continue-at)
            COMPREPLY=( $(compgen -W "-" -- "$cur") ); return 0 ;;
    esac

    if [[ "$cur" == -* ]]; then
        COMPREPLY=( $(compgen -W "$long_opts" -- "$cur") )
    fi
    return 0
}
complete -F _xhttp xhttp
