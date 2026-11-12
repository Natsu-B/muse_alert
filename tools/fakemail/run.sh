#!/usr/bin/env bash
# テスト用のメールサーバー (IMAP: Dovecot, SMTP: aiosmtpd) を起動する。
# 使い方: tools/fakemail/run.sh <作業ディレクトリ>
# エミュレーターからは 10.0.2.2:2525 (SMTP) / 10.0.2.2:1143 (IMAP) で届く。
# パスワードは FAKEMAIL_PASSWORD (既定 testpass)。SMTP はどのパスワードでも通る。止めるときは Ctrl-C (dovecot も一緒に止める)。
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
dir=$(realpath -m "${1:?作業ディレクトリを指定してください}")
run=$(mktemp -d "${XDG_RUNTIME_DIR:-/tmp}/fakemail.XXXXXX")
mkdir -p "$dir"/{state,home/Maildir}

sed -e "s|@DIR@|$dir|g" -e "s|@RUN@|$run|g" \
    -e "s|@USER@|$(id -un)|g" -e "s|@GROUP@|$(id -gn)|g" \
    -e "s|@UID@|$(id -u)|g" -e "s|@GID@|$(id -g)|g" \
    -e "s|@PASSWORD@|${FAKEMAIL_PASSWORD:-testpass}|g" \
    -e "s|@IMAP_PORT@|${FAKEMAIL_IMAP_PORT:-1143}|g" \
    "$here/dovecot.conf.in" > "$dir/dovecot.conf"

nix develop "$root#fakemail" -c bash -c '
    set -euo pipefail
    dovecot -c "$1/dovecot.conf"
    trap "doveadm -c \"$1/dovecot.conf\" stop; rm -rf \"$2\"" EXIT
    echo "dovecot started (log: $1/dovecot.log)"
    python3 "$3/smtp.py" "$1/home/Maildir" "$4"
' _ "$dir" "$run" "$here" "${FAKEMAIL_SMTP_PORT:-2525}"
