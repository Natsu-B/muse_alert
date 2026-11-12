"""テスト用 SMTP サーバー。Gmail と同じく、送ったメールを INBOX と Sent の両方に入れる。

使い方: python smtp.py <maildir> <port>
"""
import os
import socket
import sys
import threading
import time

from aiosmtpd.controller import Controller
from aiosmtpd.smtp import AuthResult


def deliver(maildir: str, data: bytes) -> None:
    name = f"{time.time_ns()}.{os.getpid()}.{socket.gethostname()}"
    for sub in (maildir, os.path.join(maildir, ".Sent")):
        for d in ("tmp", "new", "cur"):
            os.makedirs(os.path.join(sub, d), exist_ok=True)
        tmp = os.path.join(sub, "tmp", name)
        with open(tmp, "wb") as f:
            f.write(data)
        os.rename(tmp, os.path.join(sub, "new", name))


class Handler:
    def __init__(self, maildir: str):
        self.maildir = maildir

    async def handle_DATA(self, server, session, envelope):
        deliver(self.maildir, envelope.original_content)
        print(f"delivered from={envelope.mail_from} to={envelope.rcpt_tos}", flush=True)
        return "250 OK"


def authenticator(server, session, envelope, mechanism, auth_data):
    return AuthResult(success=True)


if __name__ == "__main__":
    maildir, port = sys.argv[1], int(sys.argv[2])
    controller = Controller(
        Handler(maildir),
        hostname="127.0.0.1",
        port=port,
        authenticator=authenticator,
        auth_require_tls=False,
    )
    controller.start()
    print(f"smtp listening on {port}", flush=True)
    threading.Event().wait()
