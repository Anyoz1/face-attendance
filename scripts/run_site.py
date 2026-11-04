#!/usr/bin/env python3
"""Run the local admin and the LAN HTTPS terminal in one process."""
import argparse
import asyncio
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import ipaddress
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import uvicorn
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from attendance.app import create_app


def addresses():
    found = []
    # Linux distributions ship different hostname implementations; -I is not portable.
    try:
        import fcntl
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            for interface in os.listdir('/sys/class/net'):
                if interface == 'lo' or interface.startswith(('br-', 'docker', 'virbr', 'veth')):
                    continue
                try:
                    packed = fcntl.ioctl(sock.fileno(), 0x8915, struct.pack('256s', interface.encode()[:15]))
                    value = socket.inet_ntoa(packed[20:24])
                    if not ipaddress.ip_address(value).is_loopback:
                        found.append(value)
                except OSError:
                    continue
    except (ImportError, OSError):
        pass
    try:
        output = subprocess.run(['hostname', '-I'], capture_output=True, text=True, check=True).stdout
        for value in output.split():
            address = ipaddress.ip_address(value)
            if address.version == 4 and not address.is_loopback:
                if value not in found:
                    found.append(value)
    except (OSError, subprocess.SubprocessError):
        pass
    # Prefer a Wi-Fi/LAN address over virtual bridges or VPN addresses.
    found.sort(key=lambda value: (value.startswith(('192.168.122.', '172.17.')), value.startswith('100.')))
    if os.environ.get('FACE_HOST'):
        chosen = str(ipaddress.ip_address(os.environ['FACE_HOST']))
        found = [chosen] + [value for value in found if value != chosen]
    return found


def write_private(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)


def tls(directory, ips):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    current = datetime.now(timezone.utc)
    ca_key_path, ca_path = directory / 'ca.key', directory / 'ca.crt'
    if ca_key_path.exists() and ca_path.exists():
        ca_key = serialization.load_pem_private_key(ca_key_path.read_bytes(), password=None)
        ca = x509.load_pem_x509_certificate(ca_path.read_bytes())
        if ca.not_valid_after_utc < current + timedelta(days=7):
            raise RuntimeError('Сертификат CA истекает. Сохраните старую папку data/tls и создайте новую; затем установите новый сертификат на телефоне.')
    else:
        ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Pereklichka Local CA')])
        ca = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
              .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
              .not_valid_before(current - timedelta(minutes=5)).not_valid_after(current + timedelta(days=365))
              .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
              .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                    data_encipherment=False, key_agreement=False, key_cert_sign=True, crl_sign=True,
                    encipher_only=False, decipher_only=False), critical=True)
              .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
              .sign(ca_key, hashes.SHA256()))
        write_private(ca_key_path, ca_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        ca_path.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Pereklichka terminal')])
    sans = [x509.DNSName('localhost'), x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]
    sans += [x509.IPAddress(ipaddress.ip_address(ip)) for ip in ips]
    certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(ca.subject)
                   .public_key(key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(current - timedelta(minutes=5)).not_valid_after(current + timedelta(days=90))
                   .add_extension(x509.SubjectAlternativeName(sans), critical=False)
                   .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                   .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                   .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
                   .sign(ca_key, hashes.SHA256()))
    key_path, cert_path = directory / 'server.key', directory / 'server.crt'
    write_private(key_path, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    return str(key_path), str(cert_path)


class Server(uvicorn.Server):
    @contextmanager
    def capture_signals(self):
        yield


async def serve(app, key, cert, http_port, https_port):
    common = {'app': app, 'host': '0.0.0.0', 'access_log': False, 'proxy_headers': False, 'log_level': 'info'}
    servers = [Server(uvicorn.Config(port=http_port, **common)),
               Server(uvicorn.Config(port=https_port, ssl_keyfile=key, ssl_certfile=cert, **common))]
    loop = asyncio.get_running_loop()
    def shutdown():
        for server in servers:
            server.should_exit = True
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, shutdown)
    await asyncio.gather(*(server.serve() for server in servers))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--http-port', type=int, default=8000)
    parser.add_argument('--https-port', type=int, default=8443)
    args = parser.parse_args()
    for port in (args.http_port, args.https_port):
        try:
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('0.0.0.0', port))
        except OSError as error:
            raise SystemExit(f'Порт {port} недоступен: {error}')
    directory = Path(os.environ.get('FACE_DATA_DIR', ROOT / 'data'))
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    ips = addresses()
    host = ips[0] if ips else 'localhost'
    key, cert = tls(directory / 'tls', ips)
    connection = {'base_url': f'https://{host}:{args.https_port}',
                  'setup_url': f'http://{host}:{args.http_port}/setup'}
    (directory / 'connection.json').write_text(json.dumps(connection))
    print(f'Панель на ноутбуке: http://localhost:{args.http_port}/admin', flush=True)
    print(f'Настройка телефона: {connection["setup_url"]}', flush=True)
    if len(ips) > 1:
        print('Адреса ноутбука: ' + ', '.join(ips) + '. Для выбора: FACE_HOST=<IP>.', flush=True)
    if not ips:
        print('LAN-адрес не найден: задайте FACE_HOST=<IP ноутбука>.', flush=True)
    print('Подключение телефона — через QR-код в панели. Остановка: Ctrl+C.', flush=True)
    asyncio.run(serve(create_app(directory), key, cert, args.http_port, args.https_port))


if __name__ == '__main__':
    main()
