FROM alpine:3.23@sha256:469c9ea410b9215eaee7976ba6c0639039b444be8e5d638f7abbada189971eb2
# Explicit administrative build; nothing is installed on the host by the commands.
RUN apk add --no-cache python3 py3-cryptography
COPY vault.py /vault.py
ENTRYPOINT ["python3", "/vault.py"]
