#!/usr/bin/env python3
# Auxiliar do GNSS do Scanner Campo (roda NO SCANNER, em /tmp: some ao reiniciar).
# Com o levantamento parado o receptor (Unicore, COM1 = /dev/ttyS1 a 460800) fica mudo: so o programa "pos" o configura.
# Este auxiliar, enquanto o "pos" NAO roda: pede o GGA/GST ao receptor, manda as linhas NMEA ao app (TCP 8081) e
# escreve no receptor a correcao RTCM que o app manda. Quando o "pos" aparece (levantamento iniciado), solta a serial na hora.
# Sai sozinho: "#FIM" do app, ou 5 min sem o app conectado. Ao sair deixa o receptor mudo de novo (UNLOGALL COM1).
import os, select, signal, socket, termios, time, tty

SERIAL = os.environ.get('SCG_SERIAL', '/dev/ttyS1')                 # SCG_*: so para o teste no PC (serial simulada)
PORTA = int(os.environ.get('SCG_PORTA', '8081'))
PROG_LEVANTAMENTO = os.environ.get('SCG_POS', 'pos')
CMDS = b'UNLOGALL COM1\r\nLOG COM1 GPGGA ONTIME 1\r\nLOG COM1 GPGST ONTIME 1\r\n'
SEM_APP_S = int(os.environ.get('SCG_SEM_APP_S', '300'))


def pos_rodando():
    for p in os.listdir('/proc'):
        if p.isdigit():
            try:
                with open('/proc/%s/comm' % p) as f:
                    if f.read().strip() == PROG_LEVANTAMENTO:
                        return True
            except OSError:
                pass
    return False


def abrir():
    fd = os.open(SERIAL, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    tty.setraw(fd)
    a = termios.tcgetattr(fd)
    a[4] = a[5] = termios.B460800
    a[2] |= termios.CLOCAL | termios.CREAD
    termios.tcsetattr(fd, termios.TCSANOW, a)
    os.write(fd, CMDS)
    return fd


class Aux:
    def __init__(self):
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(('0.0.0.0', PORTA))
        self.srv.listen(1)
        self.cli = None
        self.fd = None
        self.buf = b''
        self.ult_app = time.time()
        self.ult_rx = 0.0
        self.ult_cmd = 0.0
        self.ult_est = 0.0
        self.fim = False
        self.erro = b''

    def enviar(self, b):
        if self.cli is None:
            return
        try:
            self.cli.sendall(b)
        except OSError:
            self.fechar_cli()

    def fechar_cli(self):
        try:
            self.cli.close()
        except Exception:
            pass
        self.cli = None
        self.ult_app = time.time()

    def soltar_serial(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
            self.buf = b''

    def passo(self):
        agora = time.time()
        if pos_rodando():
            self.soltar_serial()
            estado = b'#POS\n'
        else:
            if self.fd is None and agora - self.ult_cmd > 5:
                try:
                    self.fd = abrir()
                    self.erro = b''
                except OSError as e:                                         # tenta de novo a cada 5 s
                    self.erro = ('#ERRO serial %s: %s\n' % (SERIAL, e)).encode()
                self.ult_cmd = agora
            elif self.fd is not None and agora - self.ult_rx > 5 and agora - self.ult_cmd > 5:   # receptor calado: pede de novo
                os.write(self.fd, CMDS)
                self.ult_cmd = agora
            estado = b'#OK\n' if self.fd is not None else (self.erro or b'#ABRINDO\n')
        if agora - self.ult_est >= 1:
            self.enviar(estado)
            self.ult_est = agora
        lst = [self.srv] + ([self.cli] if self.cli else []) + ([self.fd] if self.fd is not None else [])
        r, _, _ = select.select(lst, [], [], 0.5)
        for x in r:
            if x is self.srv:
                c, _ = self.srv.accept()
                c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                if self.cli:
                    self.fechar_cli()
                self.cli = c
            elif x is self.cli:
                try:
                    d = self.cli.recv(8192)
                except OSError:
                    d = b''
                if not d:
                    self.fechar_cli()
                elif d.startswith(b'#FIM'):
                    self.fim = True
                elif self.fd is not None:
                    os.write(self.fd, d)                                     # correcao RTCM do caster
            elif x == self.fd:
                try:
                    d = os.read(self.fd, 8192)
                except BlockingIOError:
                    d = b''
                if d:
                    self.ult_rx = agora
                    self.buf += d
                    # so as linhas de texto (NMEA e respostas "$command"); binario fica de fora
                    while b'\n' in self.buf:
                        linha, self.buf = self.buf.split(b'\n', 1)
                        i = linha.find(b'\x24')
                        if i >= 0:
                            self.enviar(linha[i:].rstrip(b'\r') + b'\n')
                    if len(self.buf) > 4096:
                        self.buf = b''
        if self.cli is None and agora - self.ult_app > SEM_APP_S:
            self.fim = True

    def sair(self):
        if self.fd is not None:
            try:
                os.write(self.fd, b'UNLOGALL COM1\r\n')
            except OSError:
                pass
        self.soltar_serial()
        if self.cli:
            self.fechar_cli()
        self.srv.close()


def main():
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    a = Aux()
    signal.signal(signal.SIGTERM, lambda *_: setattr(a, 'fim', True))
    try:
        while not a.fim:
            a.passo()
    finally:
        a.sair()


if __name__ == '__main__':
    main()
